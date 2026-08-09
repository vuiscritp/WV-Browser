package com.moonlite.browser

/**
 * Curated User-Agent strings so the browser can present itself as a real
 * mainstream browser. Values are point-in-time snapshots of common UA
 * strings; sites rarely validate them beyond coarse sniffing, so exact
 * version drift over time is not a functional problem for this project.
 *
 * Each preset also carries the data [FingerprintSync] needs to keep the
 * Sec-CH-UA* client-hint headers and the JS-visible navigator.userAgentData
 * / navigator.platform in agreement with the UA string above — otherwise a
 * spoofed UA string alone leaves those other two layers still reporting
 * the real device, which is exactly the kind of mismatch fingerprinting
 * checks flag. See [FingerprintSync] for how these fields get used.
 */
object UaPresets {

    /** One brand entry in the Sec-CH-UA brand list, e.g. ("Google Chrome", "124"). */
    data class Brand(val brand: String, val version: String)

    /**
     * Client-hint values for a Chromium-based preset. Left null on the
     * Preset itself for non-Chromium browsers (Firefox, Safari) — those
     * engines don't send these headers or expose navigator.userAgentData at
     * all, so "no client hints" is the truthful state to spoof, not a
     * placeholder.
     */
    data class ClientHints(
        val brands: List<Brand>,
        val uaFullVersion: String,
        val platform: String,
        val platformVersion: String,
        val mobile: Boolean,
        val architecture: String,
        val bitness: String,
        val model: String
    )

    data class Preset(
        val id: String,
        val label: String,
        val ua: String,
        // true only for the "use the real device as-is" sentinel — every
        // other preset is a spoof and should NOT set this.
        val isRealDevice: Boolean = false,
        // What navigator.platform should report under this preset.
        val navigatorPlatform: String? = null,
        // null = non-Chromium browser: no Sec-CH-UA* headers, no
        // navigator.userAgentData (matches how real Firefox/Safari behave).
        val clientHints: ClientHints? = null,
        // screen.width/height/availWidth/availHeight + devicePixelRatio.
        // Kept in sync with ControlServer's default /screenshot dimensions
        // for the mobile presets, so a screenshot always matches what the
        // page itself saw through navigator/CSS media queries — a
        // mismatch there (spoofed UA claiming "phone" while
        // screen.width still reports the physical device's real, often
        // smaller, size) is itself a fingerprinting tell.
        val screenWidth: Int? = null,
        val screenHeight: Int? = null,
        val devicePixelRatio: Double? = null
    )

    // Shared low-entropy brand list used by every Chromium-based preset —
    // real Chrome always includes a randomized "greased" brand alongside
    // the real ones, so this includes one too rather than omitting it.
    private fun chromeBrands(chromeVersion: String) = listOf(
        Brand("Not/A)Brand", "99"),
        Brand("Google Chrome", chromeVersion),
        Brand("Chromium", chromeVersion)
    )

    private const val CHROME_FULL_VERSION = "124.0.6367.113"

    // Bigger than a compact/budget phone's real CSS viewport (~360-412px)
    // on purpose — matches ControlServer's default /screenshot size, and a
    // roomier layout is what was asked for ("kích thước màn to hơn").
    private const val MOBILE_SCREEN_WIDTH = 460
    private const val MOBILE_SCREEN_HEIGHT = 980
    private const val MOBILE_DPR = 2.6

    val ALL: List<Preset> = listOf(
        Preset(
            id = "chrome_mobile",
            label = "Chrome (Mobile)",
            ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36",
            navigatorPlatform = "Linux armv8l",
            clientHints = ClientHints(
                brands = chromeBrands("124"),
                uaFullVersion = CHROME_FULL_VERSION,
                platform = "Android",
                platformVersion = "14.0.0",
                mobile = true,
                architecture = "",
                bitness = "64",
                model = ""
            ),
            screenWidth = MOBILE_SCREEN_WIDTH,
            screenHeight = MOBILE_SCREEN_HEIGHT,
            devicePixelRatio = MOBILE_DPR
        ),
        Preset(
            id = "chrome_desktop",
            label = "Chrome (Desktop)",
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36",
            navigatorPlatform = "Win32",
            clientHints = ClientHints(
                brands = chromeBrands("124"),
                uaFullVersion = CHROME_FULL_VERSION,
                platform = "Windows",
                platformVersion = "10.0.0",
                mobile = false,
                architecture = "x86",
                bitness = "64",
                model = ""
            )
        ),
        Preset(
            id = "firefox_mobile",
            label = "Firefox (Mobile)",
            ua = "Mozilla/5.0 (Android 14; Mobile; rv:126.0) Gecko/126.0 Firefox/126.0",
            navigatorPlatform = "Linux armv8l",
            // clientHints left null: real Firefox sends no Sec-CH-UA* and
            // exposes no navigator.userAgentData.
            screenWidth = MOBILE_SCREEN_WIDTH,
            screenHeight = MOBILE_SCREEN_HEIGHT,
            devicePixelRatio = MOBILE_DPR
        ),
        Preset(
            id = "firefox_desktop",
            label = "Firefox (Desktop)",
            ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0",
            navigatorPlatform = "Win32"
        ),
        Preset(
            id = "safari_mobile",
            label = "Safari (iOS)",
            ua = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1",
            navigatorPlatform = "iPhone",
            screenWidth = MOBILE_SCREEN_WIDTH,
            screenHeight = MOBILE_SCREEN_HEIGHT,
            devicePixelRatio = MOBILE_DPR
        ),
        Preset(
            id = "safari_desktop",
            label = "Safari (macOS)",
            ua = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/17.5 Safari/605.1.15",
            navigatorPlatform = "MacIntel"
        ),
        Preset(
            id = "duckduckgo_mobile",
            label = "DuckDuckGo (Mobile)",
            ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36 DuckDuckGo/5",
            navigatorPlatform = "Linux armv8l",
            // Still Chromium under the hood, so headers/JS should still
            // agree with an Android/Chrome identity, same as chrome_mobile.
            clientHints = ClientHints(
                brands = chromeBrands("124"),
                uaFullVersion = CHROME_FULL_VERSION,
                platform = "Android",
                platformVersion = "14.0.0",
                mobile = true,
                architecture = "",
                bitness = "64",
                model = ""
            ),
            screenWidth = MOBILE_SCREEN_WIDTH,
            screenHeight = MOBILE_SCREEN_HEIGHT,
            devicePixelRatio = MOBILE_DPR
        ),
        Preset(
            id = "moonlite_default",
            label = "MoonLite (WebView, cleaned)",
            ua = "", // sentinel: use system default with "; wv" stripped, see BrowserActions
            isRealDevice = true
        )
    )

    fun byId(id: String): Preset = ALL.firstOrNull { it.id == id } ?: ALL.first()
}
