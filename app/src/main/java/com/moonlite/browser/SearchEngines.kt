package com.moonlite.browser

import java.net.URLEncoder
import android.content.Context

object SearchEngines {
    data class Engine(val id: String, val label: String, val searchTemplate: String, val homepage: String)

    val ALL: List<Engine> = listOf(
        Engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q={q}", "https://duckduckgo.com/"),
        Engine("google", "Google", "https://www.google.com/search?q={q}", "https://www.google.com/"),
        Engine("bing", "Bing", "https://www.bing.com/search?q={q}", "https://www.bing.com/"),
        Engine("brave", "Brave Search", "https://search.brave.com/search?q={q}", "https://search.brave.com/"),
        Engine("startpage", "Startpage", "https://www.startpage.com/sp/search?query={q}", "https://www.startpage.com/")
    )

    var currentId: String = "duckduckgo"

    fun current(): Engine = ALL.firstOrNull { it.id == currentId } ?: ALL.first()

    fun urlFor(query: String): String = current().searchTemplate.replace("{q}", URLEncoder.encode(query, "UTF-8"))

    fun homepage(context: Context): String =
        context.getSharedPreferences("moonlite", Context.MODE_PRIVATE)
            .getString("homepage", null)
            ?.takeIf { it.isNotBlank() }
            ?: current().homepage

    fun homepageLabel(context: Context): String {
        val value = context.getSharedPreferences("moonlite", Context.MODE_PRIVATE).getString("homepage", null)
        if (value.isNullOrBlank()) return current().label
        ALL.firstOrNull { it.homepage == value }?.let { return it.label }
        return if (value == "about:blank") "Blank" else "Custom"
    }
}
