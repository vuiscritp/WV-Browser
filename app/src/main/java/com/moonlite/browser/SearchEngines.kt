package com.moonlite.browser

import java.net.URLEncoder
import android.content.Context

object SearchEngines {

    data class Engine(val id: String, val label: String, val searchTemplate: String, val homepage: String)

    val ALL: List<Engine> = listOf(
        Engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/html/?q={q}", "https://duckduckgo.com/html/"),
        Engine("google", "Google", "https://www.google.com/search?q={q}", "https://www.google.com"),
        Engine("bing", "Bing", "https://www.bing.com/search?q={q}", "https://www.bing.com"),
        Engine("brave", "Brave Search", "https://search.brave.com/search?q={q}", "https://search.brave.com"),
        Engine("startpage", "Startpage", "https://www.startpage.com/sp/search?query={q}", "https://www.startpage.com")
    )

    /** Currently selected engine id — set at startup from prefs, updated when the user switches. */
    var currentId: String = "duckduckgo"

    fun current(): Engine = ALL.firstOrNull { it.id == currentId } ?: ALL.first()

    fun urlFor(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return current().searchTemplate.replace("{q}", encoded)
    }

    fun homepage(context: Context): String =
        AppPrefsHome.get(context) ?: current().homepage

    private object AppPrefsHome {
        fun get(context: Context): String? =
            context.getSharedPreferences("moonlite", Context.MODE_PRIVATE).getString("homepage", null)
    }
}
