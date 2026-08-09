package com.moonlite.browser

import android.webkit.WebView

/**
 * Shared logic for turning a raw input string (typed by the user, or sent by
 * the terminal control API) into something the WebView actually loads.
 */
object BrowserActions {

    fun load(webView: WebView, input: String, extraHeaders: Map<String, String> = emptyMap()) {
        val url = resolve(input)
        if (extraHeaders.isEmpty()) webView.loadUrl(url) else webView.loadUrl(url, extraHeaders)
    }

    fun search(webView: WebView, query: String, extraHeaders: Map<String, String> = emptyMap()) {
        val url = SearchEngines.urlFor(query)
        if (extraHeaders.isEmpty()) webView.loadUrl(url) else webView.loadUrl(url, extraHeaders)
    }

    private fun resolve(input: String): String {
        val looksLikeUrl = input.contains(".") && !input.contains(" ")
        return when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            looksLikeUrl -> "https://$input"
            else -> SearchEngines.urlFor(input)
        }
    }
}
