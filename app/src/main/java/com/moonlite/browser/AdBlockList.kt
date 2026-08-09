package com.moonlite.browser

/**
 * A small, curated set of known ad/tracker hostnames — not an exhaustive
 * external list (no network fetch, nothing to keep updated), just enough to
 * cut the most common ad/analytics noise out of scraped pages and speed up
 * loads. Off by default (see AppPrefs "adblock_enabled"); toggle via the
 * control API's /adblock endpoint.
 */
object AdBlockList {
    private val BLOCKED_SUBSTRINGS = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.",
        "amazon-adsystem.com",
        "facebook.com/tr",
        "connect.facebook.net",
        "scorecardresearch.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "criteo.net",
        "adnxs.com",
        "moatads.com",
        "popads.net",
        "propellerads.com",
        "adsystem.com",
        "pagead2.googlesyndication.com"
    )

    fun isBlocked(url: String): Boolean = BLOCKED_SUBSTRINGS.any { url.contains(it, ignoreCase = true) }
}
