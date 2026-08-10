package com.moonlite.browser

import android.app.Application

class MoonliteApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        UiLanguage.ensureDefault()
    }
}

/**
 * Startup logging disabled.
 *
 * Kept as a package-level compatibility shim because MainActivity,
 * MoonliteService and other existing code still reference StartupLog.
 *
 * No file is created, no Downloads entry is written, and no global
 * uncaught-exception handler is installed.
 */
object StartupLog {

    fun init(app: Application) = Unit

    fun mark(message: String) = Unit

    fun crashPoint(
        point: String,
        throwable: Throwable
    ) = Unit
}
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
                        "${MediaStore.Downloads.DISPLAY_NAME}=? AND " +
                            "${MediaStore.Downloads.RELATIVE_PATH}=?",
                        arrayOf(
                            FILE_NAME,
                            Environment.DIRECTORY_DOWNLOADS + "/"
                        ),
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            Uri.withAppendedPath(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                cursor.getLong(
                                    cursor.getColumnIndexOrThrow(
                                        MediaStore.Downloads._ID
                                    )
                                ).toString()
                            )
                        } else {
                            null
                        }
                    }

                    publicUri = existing ?: resolver.insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(
                                MediaStore.Downloads.DISPLAY_NAME,
                                FILE_NAME
                            )
                            put(
                                MediaStore.Downloads.MIME_TYPE,
                                "text/plain"
                            )
                            put(
                                MediaStore.Downloads.RELATIVE_PATH,
                                Environment.DIRECTORY_DOWNLOADS
                            )
                        }
                    )
                } else {
                    val dir =
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        )

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

                    a.contentResolver
                        .openOutputStream(uri, "wa")
                        ?.use { out ->
                            out.write(text.toByteArray(Charsets.UTF_8))
                        }
                } else {
                    file?.let { f ->
                        File(f.parentFile, f.name).appendText(text)
                    }
                }
            }.onFailure {
                Log.e(TAG, "Cannot append public Downloads log", it)
            }
        }

        private fun timestamp(): String =
            SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss.SSSZ",
                Locale.US
            ).format(Date())
    }
}
