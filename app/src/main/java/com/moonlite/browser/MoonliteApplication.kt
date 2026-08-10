package com.moonlite.browser

import android.app.Application
import android.util.Log
import java.io.File
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
        StartupLog.init(this)
        StartupLog.mark("APPLICATION:onCreate")
    }
}

object StartupLog {
    private const val TAG = "MoonLiteStartup"
    private const val FILE_NAME = "startup_crash.log"
    private var file: File? = null

    fun init(app: Application) {
        file = File(app.filesDir, FILE_NAME)
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
    }

    fun crashPoint(point: String, throwable: Throwable) {
        mark("CRASH_POINT:$point ${throwable.javaClass.name}: ${throwable.message}")
        appendThrowable(throwable)
    }

    @Synchronized
    private fun appendThrowable(throwable: Throwable) {
        runCatching {
            file?.appendText(Log.getStackTraceString(throwable) + "\n")
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date())
}
