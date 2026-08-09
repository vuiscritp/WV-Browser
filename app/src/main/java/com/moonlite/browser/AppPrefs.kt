package com.moonlite.browser

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import java.security.SecureRandom
import android.util.Base64

/**
 * Shared prefs helpers used by both MainActivity (UI) and MoonliteService
 * (headless engine) so settings stay in sync no matter which side changes
 * them — they read/write the same "moonlite" file via application context.
 */
object AppPrefs {
    private const val NAME = "moonlite"

    private fun of(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun edit(context: Context): SharedPreferences.Editor = of(context).edit()

    fun defaultTargetLang(): String = Locale.getDefault().language
    fun autoTranslateEnabled(context: Context) = of(context).getBoolean("auto_translate", false)
    fun targetLang(context: Context) = of(context).getString("translate_target", defaultTargetLang()) ?: defaultTargetLang()
    fun desktopModeEnabled(context: Context) = of(context).getBoolean("desktop_mode", false)
    fun searchEngineId(context: Context) = of(context).getString("search_engine", "duckduckgo") ?: "duckduckgo"
    fun adBlockEnabled(context: Context) = of(context).getBoolean("adblock_enabled", false)
    fun compactUiEnabled(context: Context) = of(context).getBoolean("compact_ui", true)
    fun tabAnimationEnabled(context: Context) = of(context).getBoolean("tab_animation", true)
    /** Set from Settings > Language; null means "don't override, use the WebView/device default". */
    fun localeOverride(context: Context): String? = of(context).getString("locale_override", null)?.ifBlank { null }

    /** Stable per-install secret used to authenticate the localhost control API. */
    fun controlToken(context: Context): String {
        val prefs = of(context)
        val existing = prefs.getString("control_token", null)
        if (!existing.isNullOrBlank()) return existing
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString("control_token", token).apply()
        return token
    }
}
