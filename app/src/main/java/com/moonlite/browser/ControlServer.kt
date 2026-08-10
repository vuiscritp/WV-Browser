package com.moonlite.browser

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.WebView
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Local control API so a terminal (Python, C, curl, ...) can drive the
 * browser and pull page data back out as JSON — a lightweight,
 * Android-native stand-in for the parts of Playwright that don't run on
 * Android at all, or are too heavy for a phone. Operates on whichever tab is
 * currently active (via [webViewProvider]) unless a request targets tab
 * management directly.
 *
 * Auth: every endpoint except /status requires a random per-install bearer
 * token. Binding to 127.0.0.1 is not treated as a security boundary because
 * another local process can also open loopback sockets, and `adb forward`
 * exposes the socket to the connected host.
 *
 * Per-tab requests are serialized: two calls that target the same WebView
 * (e.g. two /navigate in a row) run one after another, never interleaved,
 * so a click can't land mid-navigation. Calls against *different* tabs still
 * run concurrently — NanoHTTPD already gives each connection its own
 * thread.
 *
 * Endpoints:
 *   GET  /status                    -> {"status":"ok"}
 *   POST /navigate {"url":..}       -> loads a URL in the active tab, returns scraped page
 *   POST /search  {"query":..}      -> runs a search in the active tab, returns scraped page
 *   POST /back                       -> history back in the active tab, returns scraped page
 *   POST /forward                    -> history forward in the active tab, returns scraped page
 *   POST /reload                     -> reloads the active tab, returns scraped page
 *   GET  /scrape                     -> scrapes the active tab (text + links)
 *   GET  /html                       -> full outer HTML of the active tab (capped)
 *   POST /click {"selector":..,"timeout":..?}         -> waits for + clicks a CSS selector
 *   POST /fill {"selector":..,"text":..,"timeout":..?} -> waits for + fills a CSS selector
 *   POST /hover {"selector":..,"timeout":..?}          -> waits for + dispatches hover events on a selector
 *   POST /select {"selector":..,"value":..,"timeout":..?} -> waits for + sets a <select>'s value
 *   POST /key {"key":..,"selector":..?,"timeout":..?}  -> dispatches a keydown/keyup (e.g. "Enter", "Tab")
 *   POST /scroll {"selector":..?,"by":..?,"x":..?,"y":..?} -> scrolls the page, or one element into view
 *   POST /eval {"script":..}         -> raw JS eval in the active tab, returns whatever the script returns
 *   GET  /exists?selector=            -> {"exists":bool} instant check, no waiting
 *   GET  /attribute?selector=&name=   -> {"value":..|null} of an element's attribute/property
 *   POST /wait_for_selector {"selector":..,"timeout":..?} -> blocks until selector appears
 *   GET  /screenshot?width=&height=  -> {"png_base64":..} of the active tab
 *   GET  /console                    -> recent console.log/alert/confirm/prompt entries for the active tab
 *   GET  /cookies?url=..             -> cookie string for a URL (defaults to active tab's URL)
 *   POST /cookies {"url":..,"cookies":"a=1; b=2"} -> sets cookies for a URL
 *   GET  /cookies/all?url=..         -> parsed cookie list for a URL, [{"name":..,"value":..}]
 *   POST /cookies/import {"cookies":[{"name":..,"value":..,"url":..?}]} -> bulk cookie import (storageState-like)
 *   GET  /emulate                    -> current locale/timezone/hardware/geolocation spoof for the active tab
 *   POST /emulate {"locale":..?,"timezone":..?,"latitude":..?,"longitude":..?,"accuracy":..?,"hardwareConcurrency":..?,"deviceMemory":..?}
 *                                     -> sets (any field omitted = untouched) and immediately applies
 *   GET  /adblock                    -> current adblock on/off
 *   POST /adblock {"enabled":true}   -> toggle the small built-in ad/tracker blocklist
 *   GET  /tabs                       -> list open tabs
 *   POST /tabs/new {"url":..?,"incognito":..?} -> opens a new tab
 *   POST /tabs/close {"index":..}   -> closes a tab by index
 *   POST /tabs/switch {"index":..}  -> switches active tab
 *   POST /useragent {"preset":..}   -> sets UA preset id for all tabs (see UaPresets)
 *   POST /userscript {"name":..,"match":..,"code":..,"isCss":bool} -> registers a userscript
 *   GET  /userscript                -> lists registered userscripts
 *   POST /translate {"target":..}   -> translates the active tab
 *   GET  /health                     -> {uptimeMs, tabCount, memory}
 *   GET  /proxy                      -> current proxy rule (or null)
 *   POST /proxy {"host":..,"port":..,"scheme":..?} or {"clear":true} -> app-wide proxy override
 *   GET  /stream?types=console,network -> Server-Sent Events, live console/network entries (max 55s/connection, reconnect after)
 *   GET  /stream/screenshot?width=&height=&fps= -> MJPEG live video of the active tab (max 55s/connection, reconnect after)
 *
 * Honest gaps vs. Playwright: no native PDF export (WebView exposes no
 * public print-to-PDF hook without extra plumbing), no WebRTC IP-leak
 * blocking (no public WebView toggle for it), and non-Chromium UA presets
 * (Firefox/Safari) can't fully suppress the Sec-CH-UA* request headers —
 * the engine really is Chromium underneath. See EmulationProfile and
 * FingerprintSync for what IS covered on the emulation side.
 */
class ControlServer(
    port: Int,
    private val context: android.content.Context,
    private val authToken: String,
    private val setUaPreset: (String) -> Unit,
    private val webViewProvider: () -> WebView?,
    private val tabManager: TabManager,
    private val userScriptManager: UserScriptManager,
    private val translateManager: TranslateManager,
    private val defaultTargetLang: String,
    private val adBlockEnabledProvider: () -> Boolean,
    private val setAdBlockEnabled: (Boolean) -> Unit
) : NanoHTTPD("127.0.0.1", port) {

    // Set once, at process start, by MoonliteService — see /health.
    var serviceStartTime: Long = System.currentTimeMillis()
    // Last proxy rule applied via /proxy — androidx.webkit's ProxyController
    // has no getter of its own, so this is the only record of "what's
    // currently set" for /proxy's own GET to report back.
    private var currentProxyRule: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    // Upper-bound safety net only — actual wait resolves the instant the
    // page reports finished, via TabManager.awaitNextPageFinished, so a
    // fast page returns fast instead of always paying a flat delay.
    private val navTimeoutMs = 8000L
    private val defaultSelectorTimeoutMs = 5000L
    // Bumped from 412x915 (a compact/small phone) to a bigger modern phone
    // size — matches the mobile UA presets' own spoofed screen dimensions
    // (see UaPresets) so a screenshot always reflects what the page
    // actually saw itself running at, not a different size than what was
    // reported to navigator/CSS media queries.
    private val defaultScreenshotWidth = 460
    private val defaultScreenshotHeight = 980
    // Best-effort substitute for Playwright's real "networkidle" (which
    // tracks actual in-flight network requests — not something stock
    // WebView exposes a hook for without a full request-proxying layer).
    // This instead polls whether the rendered DOM has stopped growing/
    // changing size, which is what actually matters for scraping a
    // JS-rendered SPA: content that keeps streaming in via fetch/XHR after
    // the initial onPageFinished. Adds ~200ms tax to every navigate/search
    // on an already-static page; caps out here for a still-active one.
    private val domIdleMaxWaitMs = 2000L

    private val webViewLocks = ConcurrentHashMap<WebView, Any>()
    private fun lockFor(webView: WebView): Any = webViewLocks.getOrPut(webView) { Any() }

    /** Called by TabManager when a tab closes, so its lock object doesn't leak. */
    fun forgetWebView(webView: WebView) {
        // closeTab() invokes this immediately before destroy(). Acquiring the
        // same per-WebView lock guarantees an in-flight request finishes
        // before the WebView is destroyed, avoiding evaluate/draw-vs-destroy
        // races.
        synchronized(lockFor(webView)) {
            webViewLocks.remove(webView)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            if (session.uri != "/status" && !isAuthorized(session)) {
                return jsonResponse(Response.Status.UNAUTHORIZED, errorJson("missing or invalid Authorization: Bearer <token>"))
            }
            if (requestContentLength(session) > MAX_REQUEST_BODY_BYTES) {
                return jsonResponse(Response.Status.BAD_REQUEST, errorJson("request body too large; max ${MAX_REQUEST_BODY_BYTES} bytes"))
            }
            when (session.uri) {
                "/status" -> jsonResponse(Response.Status.OK, JSONObject().put("status", "ok").put("auth", "bearer"))
                "/navigate" -> handleNavigate(session)
                "/search" -> handleSearch(session)
                "/back" -> handleHistoryNav { it.goBack() }
                "/forward" -> handleHistoryNav { it.goForward() }
                "/reload" -> handleHistoryNav { it.reload() }
                "/scrape" -> withActiveWebView { jsonResponse(Response.Status.OK, scrapePage(it)) }
                "/html" -> withActiveWebView { jsonResponse(Response.Status.OK, htmlPage(it)) }
                "/click" -> handleClick(session)
                "/fill" -> handleFill(session)
                "/hover" -> handleHover(session)
                "/select" -> handleSelect(session)
                "/key" -> handleKey(session)
                "/scroll" -> handleScroll(session)
                "/eval" -> handleEval(session)
                "/exists" -> handleExists(session)
                "/attribute" -> handleAttribute(session)
                "/elements" -> handleElements(session)
                "/upload" -> handleUpload(session)
                "/wait_for_selector" -> handleWaitForSelector(session)
                "/screenshot" -> handleScreenshot(session)
                "/console" -> handleConsole()
                "/network" -> handleNetwork()
                "/cookies" -> handleCookies(session)
                "/cookies/all" -> handleCookiesAll(session)
                "/cookies/import" -> handleCookiesImport(session)
                "/emulate" -> handleEmulate(session)
                "/proxy" -> handleProxy(session)
                "/health" -> jsonResponse(Response.Status.OK, handleHealth())
                "/stream" -> handleStream(session)
                "/stream/screenshot" -> handleScreenshotStream(session)
                "/adblock" -> handleAdblock(session)
                "/tabs" -> jsonResponse(Response.Status.OK, listTabsJson())
                "/tabs/new" -> handleNewTab(session)
                "/tabs/close" -> handleCloseTab(session)
                "/tabs/switch" -> handleSwitchTab(session)
                "/useragent" -> handleSetUa(session)
                "/userscript" -> handleUserScript(session)
                "/translate" -> handleTranslate(session)
                else -> jsonResponse(Response.Status.NOT_FOUND, errorJson("unknown endpoint"))
            }
        } catch (e: Exception) {
            jsonResponse(Response.Status.INTERNAL_ERROR, errorJson(e.message ?: "server error"))
        }
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val header = session.headers["authorization"] ?: session.headers["Authorization"] ?: return false
        val prefix = "Bearer "
        if (!header.startsWith(prefix, ignoreCase = true)) return false
        val supplied = header.substring(prefix.length).trim()
        return supplied.isNotEmpty() && java.security.MessageDigest.isEqual(
            supplied.toByteArray(Charsets.UTF_8),
            authToken.toByteArray(Charsets.UTF_8)
        )
    }

    private fun requestContentLength(session: IHTTPSession): Long =
        session.headers["content-length"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    private fun withActiveWebView(block: (WebView) -> Response): Response {
        val webView = webViewProvider() ?: return noActiveTab()
        return synchronized(lockFor(webView)) { block(webView) }
    }

    private fun handleNavigate(session: IHTTPSession): Response = withActiveWebView { webView ->
        val body = readBody(session)
        val url = body.optString("url", "")
        if (url.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'url'"))
        awaitNavigation(webView) { BrowserActions.load(webView, url, acceptLanguageHeaderFor(webView)) }
        jsonResponse(Response.Status.OK, scrapePage(webView))
    }

    private fun handleSearch(session: IHTTPSession): Response = withActiveWebView { webView ->
        val body = readBody(session)
        val query = body.optString("query", "")
        if (query.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'query'"))
        awaitNavigation(webView) { BrowserActions.search(webView, query, acceptLanguageHeaderFor(webView)) }
        jsonResponse(Response.Status.OK, scrapePage(webView))
    }

    /**
     * Only covers the top-level document request — `WebView.loadUrl(url,
     * headers)` is a real, documented API for that one request, but there
     * is no public hook to force this header onto every subsequent
     * sub-resource/XHR/fetch the page's own JS makes afterward (doing that
     * reliably would mean proxying every request by hand — rewriting
     * bodies, redirects, streaming responses — a whole project on its own,
     * not a safe thing to bolt on here). For the common case this still
     * matters: a site's *initial* language/geo redirect almost always
     * keys off this exact header on the first request.
     */
    private fun acceptLanguageHeaderFor(webView: WebView): Map<String, String> {
        val locale = tabManager.getEmulation(webView)?.locale ?: return emptyMap()
        val primary = locale.substringBefore('-')
        return mapOf("Accept-Language" to "$locale,$primary;q=0.9")
    }

    private fun handleClick(session: IHTTPSession): Response = withActiveWebView { webView ->
        val body = readBody(session)
        val selector = body.optString("selector", "")
        val timeout = body.optLong("timeout", defaultSelectorTimeoutMs)
        if (selector.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
        if (!waitForSelector(webView, selector, timeout)) {
            return@withActiveWebView jsonResponse(Response.Status.OK, JSONObject().put("ok", false).put("error", "selector not found within timeout"))
        }
        val script = withDeepQuery("""
            (function() {
                var el = ${dq(selector)};
                if (!el) return JSON.stringify({ok:false,error:'not found'});
                el.scrollIntoView({block:'center'});
                el.click();
                return JSON.stringify({ok:true});
            })();
        """.trimIndent())
        jsonResponse(Response.Status.OK, parseJsJsonResult(evaluateJsSync(webView, script)))
    }

    private fun handleFill(session: IHTTPSession): Response = withActiveWebView { webView ->
        val body = readBody(session)
        val selector = body.optString("selector", "")
        val text = body.optString("text", "")
        val timeout = body.optLong("timeout", defaultSelectorTimeoutMs)
        if (selector.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
        if (!waitForSelector(webView, selector, timeout)) {
            return@withActiveWebView jsonResponse(Response.Status.OK, JSONObject().put("ok", false).put("error", "selector not found within timeout"))
        }
        // Sets the value through the native property setter (not `el.value =`
        // directly) and fires input/change events, so React/Vue-style
        // controlled inputs pick up the change instead of silently ignoring it.
        val script = withDeepQuery("""
            (function() {
                var el = ${dq(selector)};
                if (!el) return JSON.stringify({ok:false,error:'not found'});
                el.focus();
                var proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
                var setter = Object.getOwnPropertyDescriptor(proto, 'value');
                if (setter && setter.set) { setter.set.call(el, ${JSONObject.quote(text)}); } else { el.value = ${JSONObject.quote(text)}; }
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return JSON.stringify({ok:true});
            })();
        """.trimIndent())
        jsonResponse(Response.Status.OK, parseJsJsonResult(evaluateJsSync(webView, script)))
    }

    private fun handleHover(session: IHTTPSession): Response = withActiveWebView { webView ->
        val body = readBody(session)
        val selector = body.optString("selector", "")
        val timeout = body.optLong("timeout", defaultSelectorTimeoutMs)
        if (selector.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
        if (!waitForSelector(webView, selector, timeout)) {
            return@withActiveWebView jsonResponse(Response.Status.OK, JSONObject().put("ok", false).put("error", "selector not found within timeout"))
        }
        // WebView has no real pointer to move, so this dispatches the same
        // events a mouse-enter would fire — enough for the very common case
        // of sites that show a dropdown/tooltip on :hover via a JS listener
        // rather than pure CSS (pure-CSS :hover can't be triggered from JS
        // at all, on any engine — that's a genuine platform limit here).
        val script = withDeepQuery("""
            (function() {
                var el = ${dq(selector)};
                if (!el) return JSON.stringify({ok:false,error:'not found'});
                el.scrollIntoView({block:'center'});
                var rect = el.getBoundingClientRect();
                var opts = {bubbles:true, cancelable:true, clientX: rect.left + rect.width/2, clientY: rect.top + rect.height/2};
                el.dispatchEvent(new MouseEvent('mouseover', opts));
                el.dispatchEvent(new MouseEvent('mouseenter', opts));
                el.dispatchEvent(new MouseEvent('mousemove', opts));
                return JSON.stringify({ok:true});
            })();
        """.trimIndent())
        jsonResponse(Response.Status.OK, parseJsJsonResult(evaluateJsSync(webView, script)))
    }

    private fun handleSelect(session: IHTTPSession): Response = withActiveWebView { webView ->
        val body = readBody(session)
        val selector = body.optString("selector", "")
        val value = body.optString("value", "")
        val timeout = body.optLong("timeout", defaultSelectorTimeoutMs)
        if (selector.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
        if (!waitForSelector(webView, selector, timeout)) {
            return@withActiveWebView jsonResponse(Response.Status.OK, JSONObject().put("ok", false).put("error", "selector not found within timeout"))
        }
        val script = withDeepQuery("""
            (function() {
                var el = ${dq(selector)};
                if (!el) return JSON.stringify({ok:false,error:'not found'});
                el.value = ${JSONObject.quote(value)};
                el.dispatchEvent(new Event('input', {bubbles:true}));
                el.dispatchEvent(new Event('change', {bubbles:true}));
                return JSON.stringify({ok:true});
            })();
        """.trimIndent())
        jsonResponse(Response.Status.OK, parseJsJsonResult(evaluateJsSync(webView, script)))
    }

    private fun handleKey(session: IHTTPSession): Response = withActiveWebView { webView ->
        val body = readBody(session)
        val key = body.optString("key", "")
        val selector = body.optString("selector", "")
        val timeout = body.optLong("timeout", defaultSelectorTimeoutMs)
        if (key.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'key'"))
        if (selector.isNotBlank() && !waitForSelector(webView, selector, timeout)) {
            return@withActiveWebView jsonResponse(Response.Status.OK, JSONObject().put("ok", false).put("error", "selector not found within timeout"))
        }
        val script = withDeepQuery("""
            (function() {
                var el = ${if (selector.isBlank()) "document.activeElement" else dq(selector)};
                if (!el) return JSON.stringify({ok:false,error:'not found'});
                var opts = {key: ${JSONObject.quote(key)}, bubbles:true, cancelable:true};
                el.dispatchEvent(new KeyboardEvent('keydown', opts));
                el.dispatchEvent(new KeyboardEvent('keypress', opts));
                el.dispatchEvent(new KeyboardEvent('keyup', opts));
                return JSON.stringify({ok:true});
            })();
        """.trimIndent())
        jsonResponse(Response.Status.OK, parseJsJsonResult(evaluateJsSync(webView, script)))
    }

    private fun handleScroll(session: IHTTPSession): Response = withActiveWebView { webView ->
        val body = readBody(session)
        val selector = body.optString("selector", "")
        val script = if (selector.isNotBlank()) {
            withDeepQuery("""
            (function() {
                var el = ${dq(selector)};
                if (!el) return JSON.stringify({ok:false,error:'not found'});
                el.scrollIntoView({block:'center'});
                return JSON.stringify({ok:true});
            })();
            """.trimIndent())
        } else {
            val x = body.optInt("x", 0)
            val y = body.optInt("y", body.optInt("by", 800))
            """
            (function() {
                window.scrollBy($x, $y);
                return JSON.stringify({ok:true});
            })();
            """.trimIndent()
        }
        jsonResponse(Response.Status.OK, parseJsJsonResult(evaluateJsSync(webView, script)))
    }

    private fun handleEval(session: IHTTPSession): Response = withActiveWebView { webView ->
        val body = readBody(session)
        val script = body.optString("script", "")
        if (script.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'script'"))
        // Wraps the caller's expression so both plain values ("document.title")
        // and statements work, and so the result always comes back as JSON
        // rather than evaluateJavascript's raw (often double-encoded) string.
        // __mlDeepQuery is available here too, for callers that want
        // shadow-DOM/iframe-piercing lookups from raw script.
        val wrapped = withDeepQuery("""
            (function() {
                try {
                    var __mlResult = (function() { return ($script); })();
                    return JSON.stringify({ok:true, result: __mlResult});
                } catch (e) {
                    return JSON.stringify({ok:false, error: String(e)});
                }
            })();
        """.trimIndent())
        jsonResponse(Response.Status.OK, parseJsJsonResult(evaluateJsSync(webView, wrapped)))
    }

    private fun handleExists(session: IHTTPSession): Response = withActiveWebView { webView ->
        val selector = session.parameters["selector"]?.firstOrNull() ?: ""
        if (selector.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
        val script = withDeepQuery("${dq(selector)} != null")
        jsonResponse(Response.Status.OK, JSONObject().put("exists", evaluateJsSync(webView, script) == "true"))
    }

    private fun handleAttribute(session: IHTTPSession): Response = withActiveWebView { webView ->
        val selector = session.parameters["selector"]?.firstOrNull() ?: ""
        val name = session.parameters["name"]?.firstOrNull() ?: ""
        if (selector.isBlank() || name.isBlank()) {
            return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'selector' or 'name'"))
        }
        // Checks the live DOM property first (so e.g. "value" on an input
        // reflects what the user/script actually typed, not just the
        // original HTML attribute), falling back to getAttribute for
        // anything that's attribute-only.
        val script = withDeepQuery("""
            (function() {
                var el = ${dq(selector)};
                if (!el) return JSON.stringify({found:false});
                var v = (${JSONObject.quote(name)} in el) ? el[${JSONObject.quote(name)}] : el.getAttribute(${JSONObject.quote(name)});
                return JSON.stringify({found:true, value: v === undefined ? null : v});
            })();
        """.trimIndent())
        jsonResponse(Response.Status.OK, parseJsJsonResult(evaluateJsSync(webView, script)))
    }

    private fun handleElements(session: IHTTPSession): Response = withActiveWebView { webView ->
        val selector = session.parameters["selector"]?.firstOrNull() ?: ""
        val limit = session.parameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 100
        if (selector.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
        // Bulk read — ≈ Playwright's `$$eval`: one call for every match
        // instead of looping /attribute or /eval per element from the
        // caller's side. Attributes come back as a plain object per
        // element (not just text) since a table/listing scrape usually
        // needs an href or data-id alongside the visible text.
        val script = withDeepQuery("""
            (function() {
                var els = __mlDeepQueryAll(${JSONObject.quote(selector)});
                var out = [];
                for (var i = 0; i < els.length && i < $limit; i++) {
                    var el = els[i];
                    var attrs = {};
                    for (var a = 0; a < el.attributes.length; a++) {
                        attrs[el.attributes[a].name] = el.attributes[a].value;
                    }
                    out.push({tag: el.tagName.toLowerCase(), text: (el.textContent || '').trim().substring(0, 500), attributes: attrs});
                }
                return JSON.stringify({count: els.length, elements: out});
            })();
        """.trimIndent())
        jsonResponse(Response.Status.OK, parseJsJsonResult(evaluateJsSync(webView, script)))
    }

    /**
     * Stages a file for the *next* click on a file input, then (optionally)
     * performs that click itself — Playwright's `setInputFiles()` in one
     * call rather than two, since the staged file only survives until the
     * next onShowFileChooser fires on this tab (see Tab.pendingUploadUri).
     */
    private fun handleUpload(session: IHTTPSession): Response = withActiveWebView { webView ->
        val body = readBody(session)
        val filename = body.optString("filename", "upload.bin")
        val mime = body.optString("mime", "application/octet-stream")
        val contentBase64 = body.optString("content_base64", "")
        val selector = body.optString("selector", "")
        if (contentBase64.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'content_base64'"))
        if (contentBase64.length > MAX_UPLOAD_BASE64_CHARS) {
            return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("upload too large; max decoded size is ${MAX_UPLOAD_BYTES} bytes"))
        }

        val bytes = try {
            Base64.decode(contentBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("invalid base64"))
        }
        if (bytes.size > MAX_UPLOAD_BYTES) {
            return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("upload too large; max decoded size is ${MAX_UPLOAD_BYTES} bytes"))
        }
        val uploadsDir = java.io.File(context.cacheDir, "uploads").apply { mkdirs() }
        // Sanitized so a caller-supplied filename can't escape uploadsDir
        // via "../" and write somewhere else in the app's storage.
        val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "upload.bin" }
        val file = java.io.File(uploadsDir, safeName)
        file.writeBytes(bytes)
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        tabManager.setPendingUpload(webView, uri)

        if (selector.isNotBlank()) {
            val timeout = body.optLong("timeout", defaultSelectorTimeoutMs)
            if (!waitForSelector(webView, selector, timeout)) {
                return@withActiveWebView jsonResponse(Response.Status.OK, JSONObject().put("ok", false).put("error", "selector not found within timeout"))
            }
            val script = withDeepQuery("""
                (function() {
                    var el = ${dq(selector)};
                    if (!el) return JSON.stringify({ok:false,error:'not found'});
                    el.click();
                    return JSON.stringify({ok:true});
                })();
            """.trimIndent())
            jsonResponse(Response.Status.OK, parseJsJsonResult(evaluateJsSync(webView, script)))
        } else {
            // No selector given: just leaves the file staged, for a caller
            // that wants to fire its own /click separately.
            jsonResponse(Response.Status.OK, JSONObject().put("ok", true).put("staged", safeName))
        }
    }

    private fun handleHistoryNav(action: (WebView) -> Unit): Response = withActiveWebView { webView ->
        awaitNavigation(webView) { action(webView) }
        jsonResponse(Response.Status.OK, scrapePage(webView))
    }

    private fun handleWaitForSelector(session: IHTTPSession): Response = withActiveWebView { webView ->
        val body = readBody(session)
        val selector = body.optString("selector", "")
        val timeout = body.optLong("timeout", defaultSelectorTimeoutMs)
        if (selector.isBlank()) return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'selector'"))
        val found = waitForSelector(webView, selector, timeout)
        jsonResponse(Response.Status.OK, JSONObject().put("found", found))
    }

    /**
     * `__mlDeepQuery` — CSS selector lookup that also reaches into **open**
     * shadow roots (most Web Components — Shoelace, Material Web, countless
     * custom design systems — render there, invisible to a plain
     * `document.querySelector`) and same-origin iframes. Two real platform
     * walls it can't get through, on any engine, not just WebView: a
     * *closed* shadow root (`{mode: 'closed'}`) is deliberately
     * unreachable from outside JS by design, and a cross-origin iframe
     * throws a SecurityError on `.contentDocument` by the same-origin
     * policy — both caught and skipped rather than crashing the script.
     */
    private fun dq(selector: String): String = "__mlDeepQuery(${JSONObject.quote(selector)})"

    private fun withDeepQuery(body: String): String = "$DEEP_QUERY_JS\n$body"

    /** Polls the deep-query lookup on the request's own thread; doesn't block other connections. */
    private fun waitForSelector(webView: WebView, selector: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val checkScript = withDeepQuery("${dq(selector)} != null")
        while (System.currentTimeMillis() < deadline) {
            if (evaluateJsSync(webView, checkScript) == "true") return true
            Thread.sleep(150)
        }
        return false
    }


    private fun handleScreenshot(session: IHTTPSession): Response = withActiveWebView { webView ->
        val width = session.parameters["width"]?.firstOrNull()?.toIntOrNull() ?: defaultScreenshotWidth
        val height = session.parameters["height"]?.firstOrNull()?.toIntOrNull() ?: defaultScreenshotHeight
        if (width !in 1..MAX_SCREENSHOT_WIDTH || height !in 1..MAX_SCREENSHOT_HEIGHT) {
            return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("screenshot dimensions must be 1..${MAX_SCREENSHOT_WIDTH} x 1..${MAX_SCREENSHOT_HEIGHT}"))
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels > MAX_SCREENSHOT_PIXELS) {
            return@withActiveWebView jsonResponse(Response.Status.BAD_REQUEST, errorJson("screenshot too large; max ${MAX_SCREENSHOT_PIXELS} pixels"))
        }
        val png = captureScreenshot(webView, width, height)
            ?: return@withActiveWebView jsonResponse(Response.Status.INTERNAL_ERROR, errorJson("capture failed"))
        jsonResponse(Response.Status.OK, JSONObject().put("width", width).put("height", height).put("png_base64", png))
    }

    /**
     * Manually measures/lays out the WebView before drawing, so this works
     * even when the tab is running headless (detached from any window,
     * width/height would otherwise be 0). Temporarily drops to a software
     * layer for the capture — drawing a hardware-layer view onto a plain
     * Canvas while it has no window can come back blank on some Android
     * versions, and this capture only lasts one frame so the perf cost is
     * negligible.
     */
    private fun captureScreenshotBytes(webView: WebView, width: Int, height: Int, format: Bitmap.CompressFormat, quality: Int): ByteArray? {
        var result: ByteArray? = null
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                val originalLayerType = webView.layerType
                webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                webView.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
                )
                webView.layout(0, 0, width, height)

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                webView.draw(canvas)

                val stream = ByteArrayOutputStream()
                bitmap.compress(format, quality, stream)
                bitmap.recycle()
                result = stream.toByteArray()

                webView.setLayerType(originalLayerType, null)
            } catch (e: Exception) {
                result = null
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    private fun captureScreenshot(webView: WebView, width: Int, height: Int): String? {
        val bytes = captureScreenshotBytes(webView, width, height, Bitmap.CompressFormat.PNG, 90) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * MJPEG (`multipart/x-mixed-replace`) — an actual *visual* live stream
     * of the active tab, repeatedly capturing the same way `/screenshot`
     * does. This is what `/stream` (Server-Sent Events, console/network
     * *log entries*) is NOT: that one carries no pixels at all, so a page
     * that renders correctly but never calls console.log or fires matching
     * network requests looks totally blank through it — because there was
     * never anything visual in that stream to begin with. This endpoint is
     * the actual fix for "watch a JS-heavy page render, live". Needs the
     * same `Authorization: Bearer` header as every other endpoint (this
     * app deliberately never accepts the token via query string — see
     * README section 2 — so a bare `<img src=...>` tag can't authenticate
     * on its own); consume it from something that can set headers instead:
     * `curl -N -H "Authorization: Bearer $TOKEN" $BASE/stream/screenshot | ffplay -f mjpeg -i -`
     *
     * `?fps=` (default 2, max 5 — a WebView capture forces a real
     * measure+layout+draw pass on the main thread every frame; higher than
     * a few fps starts competing for main-thread time with the page's own
     * rendering, which is counterproductive for a debugging view).
     */
    private fun handleScreenshotStream(session: IHTTPSession): Response {
        val webView = webViewProvider() ?: return noActiveTab()
        val width = session.parameters["width"]?.firstOrNull()?.toIntOrNull() ?: defaultScreenshotWidth
        val height = session.parameters["height"]?.firstOrNull()?.toIntOrNull() ?: defaultScreenshotHeight
        if (width !in 1..MAX_SCREENSHOT_WIDTH || height !in 1..MAX_SCREENSHOT_HEIGHT) {
            return jsonResponse(Response.Status.BAD_REQUEST, errorJson("screenshot dimensions must be 1..${MAX_SCREENSHOT_WIDTH} x 1..${MAX_SCREENSHOT_HEIGHT}"))
        }
        if (width.toLong() * height.toLong() > MAX_SCREENSHOT_PIXELS) {
            return jsonResponse(Response.Status.BAD_REQUEST, errorJson("screenshot too large; max ${MAX_SCREENSHOT_PIXELS} pixels"))
        }
        val fps = (session.parameters["fps"]?.firstOrNull()?.toIntOrNull() ?: 2).coerceIn(1, 5)
        val frameIntervalMs = 1000L / fps
        val boundary = "moonliteframe"

        val pipeOut = java.io.PipedOutputStream()
        val pipeIn = java.io.PipedInputStream(pipeOut, 65536)

        val thread = Thread {
            val deadline = System.currentTimeMillis() + STREAM_MAX_DURATION_MS
            try {
                while (System.currentTimeMillis() < deadline) {
                    val frameStart = System.currentTimeMillis()
                    val jpeg = captureScreenshotBytes(webView, width, height, Bitmap.CompressFormat.JPEG, 70)
                    if (jpeg != null) {
                        val header = "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                        pipeOut.write(header.toByteArray(Charsets.US_ASCII))
                        pipeOut.write(jpeg)
                        pipeOut.write("\r\n".toByteArray(Charsets.US_ASCII))
                        pipeOut.flush()
                    }
                    val elapsed = System.currentTimeMillis() - frameStart
                    val sleepMs = frameIntervalMs - elapsed
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                }
            } catch (e: Exception) {
                // Client disconnected (broken pipe) or stream torn down — either way, just stop.
            } finally {
                try { pipeOut.close() } catch (e: Exception) { }
            }
        }
        thread.isDaemon = true
        thread.start()

        val response = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=$boundary", pipeIn)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("X-Accel-Buffering", "no")
        return response
    }

    private fun handleConsole(): Response {
        val webView = webViewProvider() ?: return noActiveTab()
        val tab = tabManager.tabFor(webView) ?: return noActiveTab()
        val arr = org.json.JSONArray()
        tab.consoleSnapshot().forEach { entry ->
            arr.put(
                JSONObject()
                    .put("level", entry.level)
                    .put("message", entry.message)
                    .put("source", entry.source)
                    .put("line", entry.line)
                    .put("timestamp", entry.timestamp)
            )
        }
        return jsonResponse(Response.Status.OK, JSONObject().put("entries", arr))
    }

    private fun handleNetwork(): Response {
        val webView = webViewProvider() ?: return noActiveTab()
        val tab = tabManager.tabFor(webView) ?: return noActiveTab()
        val arr = org.json.JSONArray()
        tab.networkSnapshot().forEach { entry ->
            arr.put(
                JSONObject()
                    .put("method", entry.method)
                    .put("url", entry.url)
                    .put("resourceType", entry.resourceType)
                    .put("blocked", entry.blocked)
                    .put("timestamp", entry.timestamp)
            )
        }
        return jsonResponse(Response.Status.OK, JSONObject().put("entries", arr))
    }

    private fun handleCookies(session: IHTTPSession): Response {
        val cookieManager = android.webkit.CookieManager.getInstance()
        // .url is a WebView getter (getUrl()) — must be read on the main
        // thread, unlike CookieManager's own get/set/flush which are safe
        // from any thread.
        val activeUrl = runOnMainAndGet<String?>(null) { webViewProvider()?.url }
        if (session.method == Method.GET) {
            val url = session.parameters["url"]?.firstOrNull() ?: activeUrl
                ?: return jsonResponse(Response.Status.BAD_REQUEST, errorJson("no url and no active tab"))
            val cookie = cookieManager.getCookie(url) ?: ""
            return jsonResponse(Response.Status.OK, JSONObject().put("url", url).put("cookie", cookie))
        }
        val body = readBody(session)
        val url = body.optString("url", "").ifBlank { activeUrl ?: "" }
        val cookies = body.optString("cookies", "")
        if (url.isBlank() || cookies.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'url' or 'cookies'"))
        }
        var count = 0
        cookies.split(";").forEach { pair ->
            val trimmed = pair.trim()
            if (trimmed.isNotEmpty()) {
                cookieManager.setCookie(url, trimmed)
                count++
            }
        }
        cookieManager.flush()
        return jsonResponse(Response.Status.OK, JSONObject().put("url", url).put("set", count))
    }

    private fun handleCookiesAll(session: IHTTPSession): Response {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val activeUrl = runOnMainAndGet<String?>(null) { webViewProvider()?.url }
        val url = session.parameters["url"]?.firstOrNull() ?: activeUrl
            ?: return jsonResponse(Response.Status.BAD_REQUEST, errorJson("no url and no active tab"))
        val raw = cookieManager.getCookie(url) ?: ""
        val arr = org.json.JSONArray()
        raw.split(";").forEach { pair ->
            val trimmed = pair.trim()
            if (trimmed.isNotEmpty()) {
                val name = trimmed.substringBefore("=").trim()
                val value = trimmed.substringAfter("=", "")
                arr.put(JSONObject().put("name", name).put("value", value))
            }
        }
        return jsonResponse(Response.Status.OK, JSONObject().put("url", url).put("cookies", arr))
    }

    /**
     * Bulk import — Playwright's `browserContext.addCookies()` equivalent,
     * for restoring a whole logged-in session in one call instead of one
     * /cookies POST per cookie. Each entry needs its own "url" (or "domain")
     * since a batch exported via /cookies/all from one site won't
     * necessarily all belong to the tab that's active when importing.
     */
    private fun handleCookiesImport(session: IHTTPSession): Response {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val activeUrl = runOnMainAndGet<String?>(null) { webViewProvider()?.url }
        val body = readBody(session)
        val cookies = body.optJSONArray("cookies")
            ?: return jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'cookies'"))
        var count = 0
        for (i in 0 until cookies.length()) {
            val entry = cookies.optJSONObject(i) ?: continue
            val name = entry.optString("name", "")
            val value = entry.optString("value", "")
            val url = entry.optString("url", "").ifBlank {
                entry.optString("domain", "").ifBlank { activeUrl ?: "" }
            }
            if (name.isBlank() || url.isBlank()) continue
            cookieManager.setCookie(url, "$name=$value")
            count++
        }
        cookieManager.flush()
        return jsonResponse(Response.Status.OK, JSONObject().put("imported", count))
    }

    private fun handleEmulate(session: IHTTPSession): Response {
        val webView = webViewProvider() ?: return noActiveTab()
        if (session.method == Method.GET) {
            val current = runOnMainAndGet<EmulationOverrides?>(null) { tabManager.getEmulation(webView) }
            return jsonResponse(Response.Status.OK, current?.toJson() ?: JSONObject())
        }
        val body = readBody(session)
        val existing = runOnMainAndGet<EmulationOverrides?>(null) { tabManager.getEmulation(webView) }
        val merged = EmulationOverrides(
            locale = if (body.has("locale")) body.optString("locale").ifBlank { null } else existing?.locale,
            timezoneId = if (body.has("timezone")) body.optString("timezone").ifBlank { null } else existing?.timezoneId,
            latitude = if (body.has("latitude")) body.optDouble("latitude") else existing?.latitude,
            longitude = if (body.has("longitude")) body.optDouble("longitude") else existing?.longitude,
            accuracy = if (body.has("accuracy")) body.optDouble("accuracy") else existing?.accuracy,
            hardwareConcurrency = if (body.has("hardwareConcurrency")) body.optInt("hardwareConcurrency") else existing?.hardwareConcurrency,
            deviceMemory = if (body.has("deviceMemory")) body.optInt("deviceMemory") else existing?.deviceMemory
        )
        val invalidEmulation = when {
            merged.locale != null && !Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$").matches(merged.locale) -> "invalid locale"
            merged.timezoneId != null && !java.util.TimeZone.getAvailableIDs().contains(merged.timezoneId) -> "invalid timezone"
            merged.latitude != null && (merged.latitude.isNaN() || merged.latitude !in -90.0..90.0) -> "latitude must be between -90 and 90"
            merged.longitude != null && (merged.longitude.isNaN() || merged.longitude !in -180.0..180.0) -> "longitude must be between -180 and 180"
            merged.accuracy != null && (merged.accuracy.isNaN() || merged.accuracy <= 0.0) -> "accuracy must be > 0"
            merged.hardwareConcurrency != null && merged.hardwareConcurrency !in 1..256 -> "hardwareConcurrency must be 1..256"
            merged.deviceMemory != null && merged.deviceMemory !in 1..1024 -> "deviceMemory must be 1..1024"
            else -> null
        }
        if (invalidEmulation != null) return jsonResponse(Response.Status.BAD_REQUEST, errorJson(invalidEmulation))
        runOnMainAndWait { tabManager.setEmulation(webView, merged) }
        return jsonResponse(Response.Status.OK, merged.toJson())
    }

    /**
     * Honest scope: `androidx.webkit.ProxyController` overrides the proxy
     * for the **whole app process** — every tab, not just the active one.
     * There is no per-WebView proxy API in stock WebView; a real per-tab
     * proxy would need each tab to run in its own process (WebView doesn't
     * support that) or a local proxy server this app runs and points every
     * WebView at (a much bigger feature). This is the honest, available
     * substitute — one exit IP at a time for the whole app, still useful
     * for e.g. routing everything through a residential/rotating proxy.
     */
    fun setProxy(host: String, port: Int, scheme: String = "http"): Boolean {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) return false
        if (host.isBlank() || port !in 1..65535) return false
        val normalizedScheme = scheme.lowercase().let { if (it == "https" || it == "socks5") it else "http" }
        val controller = androidx.webkit.ProxyController.getInstance()
        val config = androidx.webkit.ProxyConfig.Builder().addProxyRule("$normalizedScheme://$host:$port").build()
        val latch = CountDownLatch(1)
        controller.setProxyOverride(config, { r -> r.run() }, { latch.countDown() })
        latch.await(3, TimeUnit.SECONDS)
        currentProxyRule = "$normalizedScheme://$host:$port"
        return true
    }

    fun clearProxy(): Boolean {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) return false
        val latch = CountDownLatch(1)
        androidx.webkit.ProxyController.getInstance().clearProxyOverride({ r -> r.run() }, { latch.countDown() })
        latch.await(3, TimeUnit.SECONDS)
        currentProxyRule = null
        return true
    }

    private fun handleProxy(session: IHTTPSession): Response {
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) {
            return jsonResponse(Response.Status.OK, errorJson("proxy override not supported on this device's WebView build"))
        }
        val controller = androidx.webkit.ProxyController.getInstance()
        if (session.method == Method.GET) {
            return jsonResponse(Response.Status.OK, JSONObject().put("current", currentProxyRule ?: JSONObject.NULL))
        }
        val body = readBody(session)
        if (body.optBoolean("clear", false) || (body.optString("host", "").isBlank() && !body.has("host"))) {
            val latch = CountDownLatch(1)
            // The Executor lambda's job IS to run what it's handed — an
            // empty body here would silently never invoke `listener` at
            // all, and this call would hang until the 3s timeout below
            // every single time.
            controller.clearProxyOverride({ r -> r.run() }, { latch.countDown() })
            latch.await(3, TimeUnit.SECONDS)
            currentProxyRule = null
            return jsonResponse(Response.Status.OK, JSONObject().put("cleared", true))
        }
        val host = body.optString("host", "")
        val port = body.optInt("port", 0)
        if (host.isBlank() || port <= 0) return jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'host'/'port' (or send {\"clear\":true})"))
        val scheme = body.optString("scheme", "http") // "http", "https", or "socks5"
        val rule = "$scheme://$host:$port"
        val config = androidx.webkit.ProxyConfig.Builder().addProxyRule(rule).build()
        val latch = CountDownLatch(1)
        controller.setProxyOverride(config, { r -> r.run() }, { latch.countDown() })
        latch.await(3, TimeUnit.SECONDS)
        currentProxyRule = rule
        return jsonResponse(Response.Status.OK, JSONObject().put("current", rule))
    }

    private fun handleHealth(): JSONObject {
        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val debugMemInfo = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(debugMemInfo)
        val runtime = Runtime.getRuntime()

        return JSONObject()
            .put("uptimeMs", System.currentTimeMillis() - serviceStartTime)
            .put("tabCount", tabManager.tabCount())
            .put(
                "memory",
                JSONObject()
                    .put("appPssKb", debugMemInfo.totalPss)
                    .put("jvmUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024))
                    .put("jvmMaxMb", runtime.maxMemory() / (1024 * 1024))
                    .put("systemAvailMb", memInfo.availMem / (1024 * 1024))
                    .put("lowMemory", memInfo.lowMemory)
            )
    }

    /**
     * Server-Sent Events — pushes new `/console` and/or `/network` entries
     * for the active tab as they happen, instead of the caller polling
     * those endpoints in a loop. `?types=console,network` filters which
     * (default: both). Connect with `curl -N` or any `EventSource`/SSE
     * client; each event is one line `data: {...json...}`.
     *
     * Capped at [STREAM_MAX_DURATION_MS] per connection (the response just
     * ends there, same as any SSE stream closing) rather than running
     * forever — this is the deciding factor for whether the background
     * writer thread below is guaranteed to exit even in the case where a
     * client disconnects without NanoHTTPD's chunked-response machinery
     * noticing and closing our PipedInputStream for us. A standard
     * EventSource client reconnects automatically when a stream ends, so
     * in practice this is invisible — just call it again.
     */
    private fun handleStream(session: IHTTPSession): Response {
        val webView = webViewProvider() ?: return noActiveTab()
        val tab = tabManager.tabFor(webView) ?: return noActiveTab()
        val typesParam = session.parameters["types"]?.firstOrNull() ?: "console,network"
        val wantConsole = typesParam.contains("console")
        val wantNetwork = typesParam.contains("network")

        val pipeOut = java.io.PipedOutputStream()
        val pipeIn = java.io.PipedInputStream(pipeOut, 8192)
        val writer = java.io.PrintWriter(java.io.OutputStreamWriter(pipeOut, Charsets.UTF_8), true)

        val thread = Thread {
            var lastConsoleSize = 0
            var lastNetworkSize = 0
            var lastHeartbeat = System.currentTimeMillis()
            val deadline = System.currentTimeMillis() + STREAM_MAX_DURATION_MS
            try {
                writer.print(": connected\n\n")
                writer.flush()
                while (System.currentTimeMillis() < deadline) {
                    if (wantConsole) {
                        val snap = tab.consoleSnapshot()
                        for (i in lastConsoleSize until snap.size) {
                            val e = snap[i]
                            val json = JSONObject()
                                .put("type", "console")
                                .put("level", e.level)
                                .put("message", e.message)
                                .put("source", e.source)
                                .put("line", e.line)
                                .put("timestamp", e.timestamp)
                            writer.print("data: $json\n\n")
                        }
                        lastConsoleSize = snap.size
                    }
                    if (wantNetwork) {
                        val snap = tab.networkSnapshot()
                        for (i in lastNetworkSize until snap.size) {
                            val e = snap[i]
                            val json = JSONObject()
                                .put("type", "network")
                                .put("method", e.method)
                                .put("url", e.url)
                                .put("resourceType", e.resourceType)
                                .put("blocked", e.blocked)
                                .put("timestamp", e.timestamp)
                            writer.print("data: $json\n\n")
                        }
                        lastNetworkSize = snap.size
                    }
                    writer.flush()
                    if (writer.checkError()) break // client disconnected — the underlying pipe write failed
                    if (System.currentTimeMillis() - lastHeartbeat > 15_000L) {
                        writer.print(": keep-alive\n\n")
                        writer.flush()
                        lastHeartbeat = System.currentTimeMillis()
                    }
                    Thread.sleep(300)
                }
            } catch (e: Exception) {
                // Client disconnected (broken pipe) or stream torn down — either way, just stop.
            } finally {
                try { writer.close() } catch (e: Exception) { }
                try { pipeOut.close() } catch (e: Exception) { }
            }
        }
        thread.isDaemon = true
        thread.start()

        val response = newChunkedResponse(Response.Status.OK, "text/event-stream", pipeIn)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("Connection", "keep-alive")
        // Common reverse-proxy convention to disable response buffering for
        // a streamed body — harmless when nothing in the path checks it.
        response.addHeader("X-Accel-Buffering", "no")
        return response
    }

    private fun handleAdblock(session: IHTTPSession): Response {
        if (session.method == Method.GET) {
            return jsonResponse(Response.Status.OK, JSONObject().put("enabled", adBlockEnabledProvider()))
        }
        val body = readBody(session)
        if (!body.has("enabled")) return jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'enabled'"))
        val enabled = body.optBoolean("enabled")
        setAdBlockEnabled(enabled)
        return jsonResponse(Response.Status.OK, JSONObject().put("enabled", enabled))
    }

    /**
     * Registers a one-shot "page finished" waiter on the main thread first,
     * then kicks off [startNav] — in that order, so there's no race where
     * the page finishes before the waiter is listening. Blocks this
     * request-handling thread (NanoHTTPD already gives each connection its
     * own thread, so other in-flight requests aren't affected) until the
     * page settles or [navTimeoutMs] elapses.
     */
    private fun awaitNavigation(webView: WebView, startNav: () -> Unit) {
        val latch = CountDownLatch(1)
        mainHandler.post {
            tabManager.awaitNextPageFinished(webView, navTimeoutMs) { latch.countDown() }
            startNav()
        }
        latch.await(navTimeoutMs + 1000, TimeUnit.MILLISECONDS)
        waitForDomIdle(webView, domIdleMaxWaitMs)
    }

    /** See [domIdleMaxWaitMs] — polls until the rendered DOM stops changing size, or the cap is hit. */
    private fun waitForDomIdle(webView: WebView, maxWaitMs: Long) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var lastSize = -1
        while (System.currentTimeMillis() < deadline) {
            val size = evaluateJsSync(webView, "document.documentElement.outerHTML.length").toIntOrNull() ?: return
            if (size == lastSize) return
            lastSize = size
            Thread.sleep(200)
        }
    }

    private fun handleNewTab(session: IHTTPSession): Response {
        val body = readBody(session)
        val url = if (body.has("url")) body.optString("url") else null
        val incognito = body.optBoolean("incognito", false)
        runOnMainAndWait { tabManager.newTab(url, incognito) }
        return jsonResponse(Response.Status.OK, listTabsJson())
    }

    private fun handleCloseTab(session: IHTTPSession): Response {
        val body = readBody(session)
        val index = body.optInt("index", -1)
        if (index < 0) return jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'index'"))
        runOnMainAndWait { tabManager.closeTab(index) }
        return jsonResponse(Response.Status.OK, listTabsJson())
    }

    private fun handleSwitchTab(session: IHTTPSession): Response {
        val body = readBody(session)
        val index = body.optInt("index", -1)
        if (index < 0) return jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'index'"))
        runOnMainAndWait { tabManager.switchTo(index) }
        return jsonResponse(Response.Status.OK, listTabsJson())
    }

    private fun handleSetUa(session: IHTTPSession): Response {
        val body = readBody(session)
        val presetId = body.optString("preset", "")
        if (presetId.isBlank()) return jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'preset'"))
        val preset = UaPresets.ALL.firstOrNull { it.id == presetId }
            ?: return jsonResponse(Response.Status.BAD_REQUEST, errorJson("unknown preset: $presetId"))
        runOnMainAndWait { setUaPreset(preset.id) }
        return jsonResponse(Response.Status.OK, JSONObject().put("applied", preset.id).put("label", preset.label))
    }

    private fun handleUserScript(session: IHTTPSession): Response {
        if (session.method == Method.GET) {
            return jsonResponse(Response.Status.OK, JSONObject().put("scripts", userScriptManager.toJson()))
        }
        val body = readBody(session)
        val name = body.optString("name", "")
        val match = body.optString("match", "*")
        val code = body.optString("code", "")
        val isCss = body.optBoolean("isCss", false)
        if (name.isBlank() || code.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, errorJson("missing 'name' or 'code'"))
        }
        userScriptManager.add(UserScript(name, match, code, isCss))
        return jsonResponse(Response.Status.OK, JSONObject().put("registered", name))
    }

    private fun handleTranslate(session: IHTTPSession): Response {
        val body = readBody(session)
        val target = body.optString("target", defaultTargetLang)
        val webView = webViewProvider() ?: return noActiveTab()
        val latch = CountDownLatch(1)
        mainHandler.post {
            translateManager.translatePage(webView, target) { latch.countDown() }
        }
        latch.await(8, TimeUnit.SECONDS)
        return jsonResponse(Response.Status.OK, JSONObject().put("translated_to", target))
    }

    /** Runs [block] on the main thread and blocks the caller for the result — for the handful of calls (like WebView.getUrl()) that must happen on main. */
    private fun <T> runOnMainAndGet(default: T, block: () -> T): T {
        var result = default
        val latch = CountDownLatch(1)
        mainHandler.post {
            try {
                result = block()
            } finally {
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    private fun parseJsJsonResult(raw: String): JSONObject {
        val decoded = try {
            org.json.JSONTokener(raw).nextValue().toString()
        } catch (e: Exception) {
            raw
        }
        return try {
            JSONObject(decoded)
        } catch (e: Exception) {
            JSONObject().put("raw", raw)
        }
    }

    private fun listTabsJson(): JSONObject = runOnMainAndGet(JSONObject().put("tabs", org.json.JSONArray())) {
        // WebView.getUrl() must run on the main thread — this whole builder
        // runs there via runOnMainAndGet instead of being called directly
        // from the NanoHTTPD request thread.
        val arr = org.json.JSONArray()
        val activeWebView = webViewProvider()
        tabManager.tabsSnapshot().forEachIndexed { index, tab ->
            arr.put(
                JSONObject()
                    .put("index", index)
                    .put("title", tab.title)
                    .put("url", tab.webView.url ?: "")
                    .put("active", tab.webView === activeWebView)
                    .put("incognito", tab.incognito)
            )
        }
        JSONObject().put("tabs", arr)
    }

    private fun scrapePage(webView: WebView): JSONObject {
        val script = """
            (function() {
                var links = [];
                var as = document.querySelectorAll('a[href]');
                for (var i = 0; i < as.length && i < 200; i++) {
                    links.push({text: as[i].innerText.trim().substring(0, 200), href: as[i].href});
                }
                return JSON.stringify({
                    url: window.location.href,
                    title: document.title,
                    text: document.body ? document.body.innerText.substring(0, 20000) : "",
                    links: links
                });
            })();
        """.trimIndent()
        return parseJsJsonResult(evaluateJsSync(webView, script))
    }

    private fun htmlPage(webView: WebView): JSONObject {
        val script = """
            (function() {
                var html = document.documentElement ? document.documentElement.outerHTML : "";
                return JSON.stringify({url: window.location.href, html: html.substring(0, 500000)});
            })();
        """.trimIndent()
        return parseJsJsonResult(evaluateJsSync(webView, script))
    }

    private fun evaluateJsSync(webView: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        mainHandler.post {
            webView.evaluateJavascript(script) { value ->
                result = value ?: ""
                latch.countDown()
            }
        }
        latch.await(5, TimeUnit.SECONDS)
        return result
    }

    private fun runOnMainAndWait(action: () -> Unit) {
        val latch = CountDownLatch(1)
        mainHandler.post {
            action()
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
    }

    private fun readBody(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val postData = files["postData"] ?: session.queryParameterString ?: ""
        return try {
            JSONObject(postData)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun noActiveTab(): Response = jsonResponse(Response.Status.BAD_REQUEST, errorJson("no active tab"))

    private fun errorJson(message: String): JSONObject = JSONObject().put("error", message)

    private fun jsonResponse(status: Response.Status, json: JSONObject): Response {
        return newFixedLengthResponse(status, "application/json", json.toString())
    }

    companion object {
        private const val MAX_REQUEST_BODY_BYTES = 12L * 1024L * 1024L
        private const val STREAM_MAX_DURATION_MS = 55_000L
        private const val MAX_UPLOAD_BYTES = 8 * 1024 * 1024
        private const val MAX_UPLOAD_BASE64_CHARS = 12 * 1024 * 1024
        private const val MAX_SCREENSHOT_WIDTH = 2048
        private const val MAX_SCREENSHOT_HEIGHT = 4096
        private const val MAX_SCREENSHOT_PIXELS = 8_000_000L
    }
}

/**
 * Defines `__mlDeepQuery(selector, root?)` on first use in a given
 * evaluateJavascript call — a `document.querySelector` replacement that
 * also searches inside open shadow roots and same-origin iframes (see the
 * KDoc on [ControlServer.dq] for what it can and can't reach). Declared as
 * a plain top-level constant (not a class member) since it's pasted
 * verbatim into page-context JS strings, not executed as Kotlin.
 */
private const val DEEP_QUERY_JS = """
    function __mlDeepQuery(selector, root) {
        root = root || document;
        var direct = root.querySelector(selector);
        if (direct) return direct;
        var all = root.querySelectorAll('*');
        for (var i = 0; i < all.length; i++) {
            var el = all[i];
            if (el.shadowRoot) {
                var found = __mlDeepQuery(selector, el.shadowRoot);
                if (found) return found;
            }
            if (el.tagName === 'IFRAME') {
                try {
                    var doc = el.contentDocument;
                    if (doc) {
                        var foundInFrame = __mlDeepQuery(selector, doc);
                        if (foundInFrame) return foundInFrame;
                    }
                } catch (e) { /* cross-origin iframe: inaccessible by design, skip */ }
            }
        }
        return null;
    }
    function __mlDeepQueryAll(selector, root, out) {
        root = root || document;
        out = out || [];
        var direct = root.querySelectorAll(selector);
        for (var d = 0; d < direct.length; d++) out.push(direct[d]);
        var all = root.querySelectorAll('*');
        for (var i = 0; i < all.length; i++) {
            var el = all[i];
            if (el.shadowRoot) __mlDeepQueryAll(selector, el.shadowRoot, out);
            if (el.tagName === 'IFRAME') {
                try {
                    var doc = el.contentDocument;
                    if (doc) __mlDeepQueryAll(selector, doc, out);
                } catch (e) { /* cross-origin iframe: inaccessible by design, skip */ }
            }
        }
        return out;
    }
"""
