package com.moonlite.browser

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/** Small runtime theme layer. It deliberately avoids replacing the app's
 * AppCompat theme so existing layouts/drawables remain stable. */
object ThemeManager {
    data class Theme(val id: String, val label: String, val background: Int, val surface: Int, val accent: Int, val text: Int, val muted: Int)

    val THEMES = listOf(
        Theme("midnight", "Midnight", Color.rgb(0,0,0), Color.rgb(16,16,16), Color.rgb(96,165,250), Color.WHITE, Color.rgb(166,166,166)),
        Theme("graphite", "Graphite", Color.rgb(14,16,20), Color.rgb(27,30,36), Color.rgb(129,140,248), Color.WHITE, Color.rgb(170,176,188)),
        Theme("ocean", "Ocean", Color.rgb(4,16,27), Color.rgb(9,31,48), Color.rgb(45,212,191), Color.WHITE, Color.rgb(158,184,201))
    )

    fun current(context: android.content.Context): Theme {
        val id = context.getSharedPreferences("moonlite", android.content.Context.MODE_PRIVATE).getString("ui_theme", "midnight") ?: "midnight"
        return THEMES.firstOrNull { it.id == id } ?: THEMES.first()
    }

    fun setTheme(context: android.content.Context, id: String, customAccent: String? = null) {
        val editor = context.getSharedPreferences("moonlite", android.content.Context.MODE_PRIVATE).edit()
            .putString("ui_theme", id)
        if (customAccent != null) editor.putString("custom_accent", customAccent) else editor.remove("custom_accent")
        editor.apply()
    }

    fun customAccent(context: android.content.Context): Int? = context.getSharedPreferences("moonlite", android.content.Context.MODE_PRIVATE)
        .getString("custom_accent", null)?.let { runCatching { Color.parseColor(it) }.getOrNull() }

    fun apply(root: View, context: android.content.Context) {
        val base = current(context)
        val accent = customAccent(context) ?: base.accent
        val surface = base.surface
        fun visit(v: View) {
            when (v) {
                is EditText -> {
                    v.setTextColor(base.text)
                    v.setHintTextColor(base.muted)
                }
                is TextView -> {
                    if (v.id != android.R.id.text1) v.setTextColor(if (v.textSize < 12f) base.muted else base.text)
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        root.setBackgroundColor(base.background)
        visit(root)
        // Re-skin common card-like containers without touching WebViews.
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child is ViewGroup) {
                    child.background = GradientDrawable().apply {
                        cornerRadius = 18f
                        setColor(surface)
                        setStroke(1, ColorUtils.setAlphaComponent(accent, 35))
                    }
                }
            }
        }
    }
}
