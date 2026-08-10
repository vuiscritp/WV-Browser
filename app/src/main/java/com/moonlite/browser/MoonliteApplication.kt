package com.moonlite.browser

import android.app.Application
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort startup diagnostics. This is intentionally tiny and does not
 * depend on Android UI, WebView, or the foreground service being initialized.
 * If the process dies before Logcat can be inspected, the next launch can
 * still expose the previous crash through startup_crash.log.
 */
class MoonliteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UiLanguage.ensureDefault()
        StartupLog.init(this)
        StartupLog.mark("APPLICATION:onCreate")
    }
}

object StartupLog {
    private const val TAG = "MoonLiteStartup"
    private const val FILE_NAME = "MoonLite_startup_crash.log"
    private var file: File? = null
    private var publicUri: Uri? = null
    private var app: Application? = null

    fun init(app: Application) {
        this.app = app
        file = File(app.filesDir, FILE_NAME)
        preparePublicDownloadLog(app)
        val previous = runCatching { file?.takeIf { it.exists() }?.readText() }.getOrNull()
        if (!previous.isNullOrBlank()) {
            Log.e(TAG, "Previous startup diagnostic found:\n$previous")
        }
        mark("--- process ${android.os.Process.myPid()} ${timestamp()} ---")

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            mark("UNCAUGHT_EXCEPTION thread=${thread.name}")
            appendThrowable(throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    fun mark(message: String) {
        val line = "${timestamp()} $message\n"
        Log.d(TAG, message)
        runCatching {
            file?.appendText(line)
        }
        appendPublic(line)
    }

    fun crashPoint(point: String, throwable: Throwable) {
        mark("CRASH_POINT:$point ${throwable.javaClass.name}: ${throwable.message}")
        appendThrowable(throwable)
    }

    @Synchronized
    private fun appendThrowable(throwable: Throwable) {
        val text = Log.getStackTraceString(throwable) + "\n"
        runCatching {
            file?.appendText(text)
        }
        appendPublic(text)
    }

    private fun preparePublicDownloadLog(app: Application) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = app.contentResolver
                val existing = resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(FILE_NAME, Environment.DIRECTORY_DOWNLOADS + "/"),
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        Uri.withAppendedPath(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)).toString()
                        )
                    } else null
                }
                publicUri = existing ?: resolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                )
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                file = File(dir, FILE_NAME)
            }
        }.onFailure {
            Log.e(TAG, "Cannot prepare public Downloads log", it)
        }
    }

    @Synchronized
    private fun appendPublic(text: String) {
        runCatching {
            val a = app ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = publicUri ?: return
                a.contentResolver.openOutputStream(uri, "wa")?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            } else {
                // On Android 9 and below this is the public Downloads file.
                file?.let { f ->
                    File(f.parentFile, f.name).appendText(text)
                }
            }
        }.onFailure {
            Log.e(TAG, "Cannot append public Downloads log", it)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date())
}
