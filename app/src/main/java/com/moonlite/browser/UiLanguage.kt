package com.moonlite.browser

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object UiLanguage {
    fun ensureDefault() {
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.isEmpty) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
    }

    fun set(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun currentTag(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        return locales.get(0)?.language ?: "en"
    }
}
