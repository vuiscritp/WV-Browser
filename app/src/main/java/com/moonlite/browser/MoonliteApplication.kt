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
 * Kept as a compatibility shim because other source files still
 * reference StartupLog. No file logging, Downloads logging, or
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
