package com.moonlite.browser

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.webkit.WebViewClient
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

data class ConsoleEntry(
    val level: String,
    val message: String,
    val source: String,
    val line: Int,
    val timestamp: Long
)

data class NetworkEntry(
    val method: String,
    val url: String,
    val resourceType: String,
    val blocked: Boolean,
    val timestamp: Long
)

class Tab(val id: Int, val webView: WebView, val incognito: Boolean = false) {
    var title: String = "New Tab"
    val consoleLog: ArrayDeque<ConsoleEntry> = ArrayDeque()
    val networkLog: ArrayDeque<NetworkEntry> = ArrayDeque()
    val visitedHosts: MutableSet<String> = mutableSetOf()
    // Locale/timezone/hardware/geolocation spoofing set via ControlServer's
    // /emulate — kept per-tab (not global) so two tabs can run different
    // "personas" at once, and re-applied after every navigation since it's
    // a JS-layer override that a fresh document doesn't inherit on its own.
    var emulationOverrides: EmulationOverrides? = null
    // Tracks retries for the URL currently failing to load (DNS/connect
    // blips) — see onReceivedError below. Reset on success or on a
    // genuinely different URL.
    var lastFailedUrl: String? = null
    var errorRetryCount: Int = 0
    // A file-chooser click (<input type=file>) currently waiting on a file
    // that ControlServer's /upload prepared — see Tab Manager's
    // onShowFileChooser and ControlServer.handleUpload.
    var pendingUploadUri: android.net.Uri? = null
    // Handles for document-start injections. They must be removed before a
    // persona changes; otherwise WebView executes every old script on future
    // documents and fingerprints accumulate contradictory values.
    var fingerprintScriptHandler: ScriptHandler? = null
    var emulationScriptHandler: ScriptHandler? = null

    fun logConsole(entry: ConsoleEntry) = synchronized(consoleLog) {
        consoleLog.addLast(entry)
        while (consoleLog.size > MAX_CONSOLE_ENTRIES) consoleLog.removeFirst()
    }

    fun consoleSnapshot(): List<ConsoleEntry> = synchronized(consoleLog) { consoleLog.toList() }

    fun logNetwork(entry: NetworkEntry) = synchronized(networkLog) {
        networkLog.addLast(entry)
        while (networkLog.size > MAX_NETWORK_ENTRIES) networkLog.removeFirst()
    }

    fun networkSnapshot(): List<NetworkEntry> = synchronized(networkLog) { networkLog.toList() }

    companion object {
        private const val MAX_CONSOLE_ENTRIES = 200
        private const val MAX_NETWORK_ENTRIES = 300
    }
}

/**
 * Owns a set of WebView instances (one per tab). WebViews live for as long
 * as the tab is open, independent of whether any UI is currently attached —
 * TabManager itself never holds a container. A caller (MainActivity, when
 * visible) attaches the active tab's WebView into its own layout via
 * [attachActiveTo] and detaches it via [detachFrom] when going to the
 * background; navigation, JS and cookie writes keep running headless in
 * between since the WebView itself is never touched by attach/detach.
 */
class TabManager(
    private val context: Context,
    private val userScriptManager: UserScriptManager,
    // Was `() -> String`; now hands back the whole Preset so FingerprintSync
    // can also sync the Sec-CH-UA* headers and navigator.userAgentData/
    // platform to match — not just the raw UA string.
    private val currentPresetProvider: () -> UaPresets.Preset,
    private val onTabsChanged: () -> Unit,
    private val onActiveTitleChanged: (String) -> Unit,
    private val onPageFinishedExtra: (WebView, String?) -> Unit = { _, _ -> },
    private val desktopModeProvider: () -> Boolean = { false },
    private val adBlockProvider: () -> Boolean = { false },
    private val onTabClosed: (WebView) -> Unit = {},
    // Fired when a Cloudflare/anti-bot challenge page is detected, so the
    // caller (MoonliteService) can push a notification telling whoever's
    // holding the phone to go solve it by hand — a script polling the
    // control API has no way to click a checkbox itself.
    private val onChallengeDetected: (String) -> Unit = {},
    // Baseline emulation applied to every *newly created* tab (e.g. the
    // Settings > Language override) — separate from setEmulation(), which
    // is a one-off per-tab call from /emulate. Without this, picking a
    // language in Settings would only ever have affected whichever tab
    // happened to be active at that exact moment, and every tab opened
    // afterward would silently go back to no override at all.
    private val defaultEmulationProvider: () -> EmulationOverrides? = { null },
    // Where every tab that isn't the one currently shown in MainActivity's
    // container gets parked — see OverlayHost's own doc for why. Nullable
    // default keeps this optional so tests/other callers don't need one.
    private val overlayHost: OverlayHost? = null
) {
    private val tabs = mutableListOf<Tab>()
    private var activeIndex = -1
    private var nextId = 1

    // One-shot callbacks fired the next time a given WebView finishes
    // loading (success or error). Lets ControlServer await "navigation
    // settled" instead of a blind fixed-length sleep — a fast page returns
    // as soon as it's actually ready, a slow one is still bounded by the
    // caller's own timeout.
    private val navWaiters = mutableMapOf<WebView, MutableList<() -> Unit>>()
    private val mainHandler = Handler(context.mainLooper)

    // Cookie flush is disk I/O; running it off the main thread keeps it from
    // ever stalling page rendering or the control API's response. It still
    // happens right after every navigation (not just "eventually") because
    // this app is meant to keep running headless and can be killed by the
    // OS at any time, so cookies need to actually be committed to disk soon
    // after each nav for long-term persistence to be reliable.
    private val cookieFlushThread = HandlerThread("moonlite-cookie-flush").apply { start() }
    private val cookieFlushHandler = Handler(cookieFlushThread.looper)

@Synchronized
    fun tabCount() = tabs.size
@Synchronized
    fun tabsSnapshot(): List<Tab> = tabs.toList()
@Synchronized
    fun activeTab(): Tab? = tabs.getOrNull(activeIndex)
@Synchronized
    fun activeWebView(): WebView? = activeTab()?.webView
@Synchronized
    fun tabFor(webView: WebView): Tab? = tabs.firstOrNull { it.webView === webView }

@Synchronized
    fun newTab(loadUrl: String? = SearchEngines.homepage(context), incognito: Boolean = false): Tab {
        val webView = WebView(context)
        val tab = Tab(nextId++, webView, incognito)
        // Add the tab before configureWebView so document-start script
        // handlers can be owned by the Tab and removed safely on close.
        tabs.add(tab)
        configureWebView(webView, tab)
        defaultEmulationProvider()?.let { setEmulation(webView, it) }
        // Parked in the overlay immediately — from the moment a tab is
        // created it's attached to a real window, never fully detached,
        // whether or not MainActivity ever ends up showing it.
        overlayHost?.parkTab(webView)
        activeIndex = tabs.size - 1
        if (loadUrl != null) BrowserActions.load(webView, loadUrl)
        onActiveTitleChanged(tab.title)
        onTabsChanged()
        return tab
    }

@Synchronized
    fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        navWaiters.remove(tab.webView)
        onTabClosed(tab.webView)

        // Android WebView uses one CookieManager/storage store for all WebViews
        // in a process. Never try to "clean up" an incognito tab by deleting
        // cookies for visited hosts: those cookies may have been created by a
        // normal tab and deleting them logs the normal tab out. Incognito is
        // therefore explicitly best-effort, not a security-isolated profile.

        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.fingerprintScriptHandler?.remove()
        tab.emulationScriptHandler?.remove()
        tab.fingerprintScriptHandler = null
        tab.emulationScriptHandler = null
        tab.webView.destroy()

        if (tabs.isEmpty()) {
            newTab()
            return
        }
        activeIndex = index.coerceAtMost(tabs.size - 1)
        onActiveTitleChanged(tabs[activeIndex].title)
        onTabsChanged()
    }

@Synchronized
    fun switchTo(index: Int) {
        if (index !in tabs.indices) return
        activeIndex = index
        onActiveTitleChanged(tabs[index].title)
        onTabsChanged()
    }

    /**
     * Attaches the active tab's WebView into [container], moving it out of
     * wherever it was — including out of the overlay, if it was parked
     * there. Whatever WebView [container] held *before* this call (the tab
     * being switched away from, if any) goes to the overlay instead of
     * being silently orphaned — this used to just call
     * `container.removeAllViews()` and drop it on the floor, which meant
     * switching tabs left the previous tab's WebView fully detached from
     * any window, the same underlying problem as the whole
     * background-tab-doesn't-render issue, just triggered by a tab switch
     * instead of backgrounding the app.
     */
    fun attachActiveTo(container: ViewGroup) {
        val webView = activeWebView() ?: return
        if (webView.parent === container && container.childCount == 1) return
        val previous = container.getChildAt(0) as? WebView
        container.removeAllViews()
        if (previous != null && previous !== webView) overlayHost?.parkTab(previous)
        (webView.parent as? ViewGroup)?.removeView(webView)
        container.addView(
            webView,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    /**
     * Detaches whatever is currently shown in [container] — typically
     * called when MainActivity goes away (onStop). Parks it in the overlay
     * instead of leaving it fully unattached, so it keeps rendering/
     * updating in the background instead of going dark the moment the UI
     * closes. Falls back to the old "just detach, nothing" behavior only
     * when the overlay permission isn't granted (OverlayHost.parkTab is a
     * no-op in that case) — tabs keep running headless either way, this
     * only affects whether the engine still considers them "visible".
     */
    fun detachFrom(container: ViewGroup) {
        val webView = container.getChildAt(0) as? WebView
        container.removeAllViews()
        if (webView != null) overlayHost?.parkTab(webView)
    }

    /** Applies the currently selected UA preset to every open tab and reloads them. */
    fun applyUaToAllTabs() {
        val preset = currentPresetProvider()
        tabs.forEach { tab ->
            applyUa(tab.webView, preset)
            tab.webView.reload()
        }
    }

    /** Sets (or clears, via null) locale/timezone/hardware/geolocation spoofing for one tab. */
    fun setEmulation(webView: WebView, overrides: EmulationOverrides?) {
        val tab = tabFor(webView) ?: return
        tab.emulationOverrides = overrides
        tab.emulationScriptHandler?.remove()
        tab.emulationScriptHandler = null
        val js = EmulationProfile.buildJs(overrides)
        if (js.isEmpty()) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            tab.emulationScriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
        }
        // Also apply immediately to the currently loaded document. The
        // document-start handler takes over on the next navigation.
        webView.evaluateJavascript(js, null)
    }

    fun getEmulation(webView: WebView): EmulationOverrides? = tabFor(webView)?.emulationOverrides

    /**
     * Parks every tab that ISN'T the active one into the overlay — used
     * right after the "Display over other apps" permission is granted
     * from Settings, so tabs that were already open (and sitting fully
     * detached from before the permission existed) get picked up
     * immediately instead of waiting for the next tab switch/app close to
     * happen to notice the permission is there now.
     */
    fun parkInactiveTabsInOverlay() {
        val active = activeWebView()
        tabs.forEach { tab ->
            if (tab.webView !== active) overlayHost?.parkTab(tab.webView)
        }
    }

    /** Stages a file for the next <input type=file> click on this tab — see WebChromeClient.onShowFileChooser above. */
    fun setPendingUpload(webView: WebView, uri: android.net.Uri) {
        tabFor(webView)?.pendingUploadUri = uri
    }

    fun networkLogFor(webView: WebView): List<NetworkEntry> = tabFor(webView)?.networkSnapshot() ?: emptyList()

    /**
     * Calls [onSettled] the next time [webView] finishes loading (or fails),
     * or after [timeoutMs] if that never happens — whichever comes first.
     * Never blocks the calling thread; must be called from the main thread.
     */
    fun awaitNextPageFinished(webView: WebView, timeoutMs: Long, onSettled: () -> Unit) {
        val fired = AtomicBoolean(false)
        val complete = {
            if (fired.compareAndSet(false, true)) onSettled()
        }
        navWaiters.getOrPut(webView) { mutableListOf() }.add(complete)
        mainHandler.postDelayed({ complete() }, timeoutMs)
    }

    private fun fireNavWaiters(webView: WebView) {
        val waiters = navWaiters.remove(webView) ?: return
        waiters.forEach { it() }
    }

    private fun applyUa(webView: WebView, preset: UaPresets.Preset) {
        val tab = tabFor(webView)
        tab?.fingerprintScriptHandler?.remove()
        tab?.fingerprintScriptHandler = null
        webView.settings.userAgentString = if (preset.ua.isBlank()) {
            webView.settings.userAgentString.replace("; wv", "")
        } else {
            preset.ua
        }
        // Keeps the Sec-CH-UA* headers and JS-visible navigator state in
        // agreement with the UA string. The old document-start script is
        // removed first so presets never accumulate.
        tab?.fingerprintScriptHandler = FingerprintSync.apply(webView, preset)
    }

    private fun configureWebView(webView: WebView, tab: Tab) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false

        // Speed: LOAD_DEFAULT honors Cache-Control/ETag instead of
        // re-fetching everything on every nav, and offscreenPreRaster lets
        // background (not-currently-visible) tabs pre-render so switching to
        // them is instant instead of a fresh paint.
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.offscreenPreRaster = true
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // Heavy-JS tuning: the JS engine itself (V8) is whatever the
        // installed system WebView package ships — an app can't make it
        // faster from the outside. What IS in this app's control is not
        // starving it: multiple windows/popups (common in JS-heavy web
        // apps for OAuth, payment, "open in new tab" flows) are allowed
        // instead of silently swallowed, and mixed-content is tolerated in
        // compatibility mode so a half-HTTPS heavy app doesn't half-break.
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Cloudflare (and most anti-bot checks) set a "clearance" cookie
        // after a successful challenge. Without cookies persisted, every
        // navigation looks like a brand-new visitor and the challenge loops
        // forever. This is the standard fix for that.
        val cookieManager = android.webkit.CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        applyUa(webView, currentPresetProvider())

        // Fixes exactly the symptom "site only renders in the tab I'm
        // actually looking at": a background tab's WebView here is never
        // attached to any View hierarchy at all (see attachActiveTo/
        // detachFrom below) — Chromium treats that as "not visible", so
        // document.hidden becomes true and IntersectionObserver never
        // reports anything as on-screen. Plenty of sites deliberately skip
        // rendering work (lazy images, virtualized lists, deferred
        // widgets) when they see that. This makes every tab always claim
        // to be visible and on-screen, so that self-throttling never
        // kicks in — a document-start script, so it's in place before the
        // page's own scripts get a chance to check.
        //
        // What this does NOT fix: Chromium's own internal frame-rate
        // throttling of a WebView that was never attached to a Window is
        // an engine-level decision, not something a JS override can
        // reach — no public WebView API exposes a way to force that from
        // outside. In practice this matters far more for smoothness of
        // continuous animation than for whether content loads at all,
        // which is what scraping actually needs.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(webView, FORCE_VISIBLE_JS, setOf("*"))
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tab.title = view?.title ?: url ?: "New Tab"
                url?.let { u -> android.net.Uri.parse(u).host?.let { tab.visitedHosts.add(it) } }
                // A page that finished loading, successfully or as an error
                // page, is not "still failing" — clears the retry counter
                // below so the next distinct navigation starts clean.
                tab.lastFailedUrl = null
                tab.errorRetryCount = 0
                if (activeTab() === tab) onActiveTitleChanged(tab.title)
                onTabsChanged()

                // Skip userscript/translate injection on anti-bot challenge
                // pages (Cloudflare etc.) — running extra JS there can
                // interfere with the challenge's own script and cause it to
                // loop instead of completing.
                val isChallengePage = (view?.title ?: "").let {
                    it.contains("Just a moment", ignoreCase = true) ||
                        it.contains("Attention Required", ignoreCase = true) ||
                        it.contains("Checking your browser", ignoreCase = true)
                } || (url ?: "").contains("cdn-cgi/challenge-platform")

                if (isChallengePage) {
                    onChallengeDetected(url ?: "")
                }

                // Emulation overrides are a plain JS-layer spoof, not
                // something that would interfere with a real challenge
                // script the way an arbitrary userscript might — so unlike
                // the block below, this one runs even on challenge pages.
                tab.emulationOverrides?.let { overrides ->
                    view?.evaluateJavascript(EmulationProfile.buildJs(overrides), null)
                }

                if (!isChallengePage) {
                    userScriptManager.buildInjectionFor(url)?.let { js ->
                        view?.evaluateJavascript(js, null)
                    }
                    if (desktopModeProvider() && view != null) {
                        view.evaluateJavascript(FORCE_DESKTOP_VIEWPORT_JS, null)
                    }
                    if (view != null) onPageFinishedExtra(view, url)
                }

                if (!tab.incognito) {
                    cookieFlushHandler.post { cookieManager.flush() }
                }

                if (view != null) fireNavWaiters(view)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (view == null) return
                if (request == null) { fireNavWaiters(view); return }
                if (!request.isForMainFrame) return // sub-resource failure (an image, analytics ping...) — not a page-level error

                val failedUrl = request.url.toString()
                val errorCode = error?.errorCode
                val isTransient = errorCode != null && errorCode in TRANSIENT_ERROR_CODES

                if (isTransient) {
                    if (tab.lastFailedUrl != failedUrl) {
                        tab.lastFailedUrl = failedUrl
                        tab.errorRetryCount = 0
                    }
                    if (tab.errorRetryCount < MAX_ERROR_RETRIES) {
                        tab.errorRetryCount++
                        val delayMs = 700L * tab.errorRetryCount
                        // DNS/connect failures on mobile are very often a
                        // one-off blip (cell tower handoff, brief DNS
                        // cache miss) rather than the site actually being
                        // down — a short backoff-and-retry silently
                        // recovers most of those without the person ever
                        // seeing an error at all.
                        mainHandler.postDelayed({ view.loadUrl(failedUrl) }, delayMs)
                        return
                    }
                }

                showErrorPage(view, failedUrl, errorCode, error?.description?.toString())
                fireNavWaiters(view)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString()
                val isBlocked = adBlockProvider() && url != null && AdBlockList.isBlocked(url)
                if (url != null) {
                    tab.logNetwork(
                        NetworkEntry(
                            method = request?.method ?: "GET",
                            url = url,
                            resourceType = guessResourceType(url),
                            blocked = isBlocked,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
                if (isBlocked) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        java.io.ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                tab.logConsole(
                    ConsoleEntry(
                        level = consoleMessage.messageLevel().name,
                        message = consoleMessage.message(),
                        source = consoleMessage.sourceId() ?: "",
                        line = consoleMessage.lineNumber(),
                        timestamp = System.currentTimeMillis()
                    )
                )
                return true
            }

            // <input type=file> click support for headless automation: a
            // human can't tap the system file picker that would normally
            // appear here. ControlServer.handleUpload writes the file to
            // disk and stashes its Uri on the tab *before* clicking the
            // input, so by the time this fires there's already a definite
            // answer ready — the real Android file-picker UI never has to
            // appear. If nothing was staged (a person tapped a file input
            // manually, with the UI actually visible), this falls through
            // to the normal system picker instead of silently doing nothing.
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val pending = tab.pendingUploadUri
                if (pending != null) {
                    tab.pendingUploadUri = null
                    filePathCallback?.onReceiveValue(arrayOf(pending))
                    return true
                }
                return false
            }

            // alert()/confirm()/prompt() otherwise block the page's JS
            // thread waiting for a human tap that will never come in
            // headless use — auto-resolving them keeps automation moving,
            // and each dialog is also recorded to the console log so it's
            // still visible to whoever is driving the tab.
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                tab.logConsole(ConsoleEntry("ALERT", message ?: "", url ?: "", 0, System.currentTimeMillis()))
                result?.confirm()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                tab.logConsole(ConsoleEntry("CONFIRM", message ?: "", url ?: "", 0, System.currentTimeMillis()))
                result?.confirm()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                tab.logConsole(ConsoleEntry("PROMPT", message ?: "", url ?: "", 0, System.currentTimeMillis()))
                result?.confirm(defaultValue ?: "")
                return true
            }
        }
    }

    /** A local, styled "can't reach this page" screen — not the bare/blank default WebView shows — with a manual retry link. */
    private fun showErrorPage(webView: WebView, failedUrl: String, errorCode: Int?, description: String?) {
        val host = try { android.net.Uri.parse(failedUrl).host ?: failedUrl } catch (e: Exception) { failedUrl }
        val reason = when (errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> "Không phân giải được DNS cho \"$host\" (ERR_NAME_NOT_RESOLVED). Có thể mạng/DNS đang chập chờn, hoặc trang này thật sự không tồn tại."
            WebViewClient.ERROR_CONNECT -> "Không kết nối được tới \"$host\"."
            WebViewClient.ERROR_TIMEOUT -> "\"$host\" phản hồi quá chậm (timeout)."
            else -> description ?: "Không tải được trang này."
        }
        val html = """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
                body { background:#000000; color:#FFFFFF; font-family:sans-serif; padding:32px 24px; text-align:center; }
                h2 { color:#FFFFFF; margin-bottom:8px; }
                p { color:#AEB0D8; font-size:14px; line-height:1.5; }
                a { display:inline-block; margin-top:20px; background:#FFFFFF; color:#FFFFFF;
                    padding:12px 28px; border-radius:24px; text-decoration:none; font-weight:bold; }
            </style></head>
            <body>
                <h2>Không mở được trang</h2>
                <p>${android.text.Html.escapeHtml(reason)}</p>
                <a href="${android.text.Html.escapeHtml(failedUrl)}">Thử lại</a>
            </body></html>
        """.trimIndent()
        webView.loadDataWithBaseURL(failedUrl, html, "text/html", "utf-8", failedUrl)
    }

    companion object {
        // WebViewClient.ERROR_HOST_LOOKUP / ERROR_CONNECT / ERROR_TIMEOUT /
        // ERROR_IO — the classes of failure that are plausibly transient
        // (a momentary DNS/connect blip) rather than "this URL is just
        // wrong" (ERROR_UNSUPPORTED_SCHEME, ERROR_BAD_URL, etc. are NOT in
        // this set — retrying those would never succeed).
        private val TRANSIENT_ERROR_CODES = setOf(-2, -6, -7, -8)
        private const val MAX_ERROR_RETRIES = 2

        // See the call site in configureWebView for why this exists.
        // Overrides the two properties (document.hidden/visibilityState)
        // that sites actually check in practice, plus document.hasFocus(),
        // and makes IntersectionObserver always report every observed
        // target as on-screen — the mechanism most "only renders lazy
        // content when scrolled/visible into view" implementations use.
        // Wrapped via Proxy rather than mutating entries directly since
        // real IntersectionObserverEntry properties are getter-based and
        // not writable on the instance itself.
        private const val FORCE_VISIBLE_JS = """
            (function() {
                try {
                    Object.defineProperty(document, 'hidden', { get: function () { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function () { return 'visible'; }, configurable: true });
                    document.hasFocus = function () { return true; };
                } catch (e) {}
                try {
                    var NativeIO = window.IntersectionObserver;
                    if (NativeIO) {
                        var PatchedIO = function (callback, options) {
                            var wrapped = function (entries, observer) {
                                var patched = entries.map(function (entry) {
                                    return new Proxy(entry, {
                                        get: function (target, prop) {
                                            if (prop === 'isIntersecting') return true;
                                            if (prop === 'intersectionRatio') return 1;
                                            return target[prop];
                                        }
                                    });
                                });
                                callback(patched, observer);
                            };
                            return new NativeIO(wrapped, options);
                        };
                        PatchedIO.prototype = NativeIO.prototype;
                        window.IntersectionObserver = PatchedIO;
                    }
                } catch (e) {}
            })();
        """

        // Many mobile-optimized sites force a narrow layout via their own
        // viewport meta tag regardless of the User-Agent. Overriding it to a
        // fixed desktop-sized width, combined with useWideViewPort +
        // loadWithOverviewMode (set above), makes the page actually render
        // like a desktop layout instead of just claiming to be Chrome.
        private const val FORCE_DESKTOP_VIEWPORT_JS = """
            (function() {
                var meta = document.querySelector('meta[name=viewport]');
                if (!meta) {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    document.head.appendChild(meta);
                }
                meta.setAttribute('content', 'width=1200');
            })();
        """
    }
}

/** Coarse best-effort classification for /network — by file extension only, no MIME sniffing. */
private fun guessResourceType(url: String): String {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".js") || path.endsWith(".mjs") -> "script"
        path.endsWith(".css") -> "stylesheet"
        path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") -> "image"
        path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") -> "font"
        path.endsWith(".json") -> "json"
        path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") -> "media"
        else -> "other"
    }
}
