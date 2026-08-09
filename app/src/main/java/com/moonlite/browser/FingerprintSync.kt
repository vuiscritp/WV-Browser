package com.moonlite.browser

import android.webkit.WebView
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ScriptHandler
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps every layer of a tab's fingerprint in agreement with the currently
 * selected [UaPresets.Preset]:
 *
 *  1. The legacy `navigator.userAgent` string (TabManager already sets this).
 *  2. The Sec-CH-UA* request headers, via WebView's Client Hints metadata API.
 *  3. The JS-visible `navigator.userAgentData` and `navigator.platform`.
 *
 * Why this exists: WebView is a real Chromium engine, so overriding only
 * the UA string leaves the Sec-CH-UA-Platform header and
 * navigator.userAgentData still reporting the device's *real* OS — that
 * split between "what the UA string claims" and "what the headers/JS
 * actually show" is exactly the kind of conflict fingerprint checkers
 * (whatismybrowser.com and real anti-bot systems alike) flag.
 *
 * Known limitation: for the Firefox/Safari presets, WebView still emits
 * at least low-entropy Sec-CH-UA* headers at the network layer because the
 * engine genuinely is Chromium — there is no public WebView API to suppress
 * that header entirely. What's covered here (JS-visible navigator state,
 * and for Chromium presets the header content itself) closes the gap that
 * matters for the vast majority of fingerprinting, both passive header
 * checks and active JS probing. Fully hiding Sec-CH-UA* on non-Chromium
 * presets would require a request-rewriting layer (e.g. proxying through
 * shouldInterceptRequest) — a bigger, separate piece of work.
 */
object FingerprintSync {

    fun apply(webView: WebView, preset: UaPresets.Preset): ScriptHandler? {
        val settings = webView.settings

        if (preset.isRealDevice) {
            // Nothing to override — the device's own real fingerprint is
            // already internally consistent by definition.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                WebSettingsCompat.setUserAgentMetadata(settings, UserAgentMetadata.Builder().build())
            }
            return null
        }

        // 1) Header-level: Sec-CH-UA / Sec-CH-UA-Platform / Sec-CH-UA-Mobile.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val ch = preset.clientHints
            val metadata = if (ch == null) {
                // Firefox/Safari: nothing truthful to claim as "brands" —
                // leave it empty rather than accidentally keep Chrome's own.
                UserAgentMetadata.Builder().build()
            } else {
                // Build the brand-version list explicitly to avoid lambda type-inference issues.
                val brandList = mutableListOf<UserAgentMetadata.BrandVersion>()
                for (b in ch.brands) {
                    val bv = UserAgentMetadata.BrandVersion.Builder()
                        .setBrand(b.brand)
                        .setMajorVersion(b.version)
                        .setFullVersion(ch.uaFullVersion)
                        .build()
                    brandList.add(bv)
                }

                UserAgentMetadata.Builder()
                    .setPlatform(ch.platform)
                    .setPlatformVersion(ch.platformVersion)
                    .setMobile(ch.mobile)
                    .setArchitecture(ch.architecture)
                    .setBitness(ch.bitness.toIntOrNull() ?: 0)
                    .setModel(ch.model)
                    .setFullVersion(ch.uaFullVersion)
                    .setBrandVersionList(brandList)
                    .build()
            }
            WebSettingsCompat.setUserAgentMetadata(settings, metadata)
        }

        // 2) JS-level: navigator.userAgentData + navigator.platform,
        // injected before any page script gets a chance to read them.
        val js = buildJs(preset)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
        } else {
            // Older system WebView build: still racy (a page script at the
            // very top of <head> can run first), but better than nothing.
            webView.evaluateJavascript(js, null)
            return null
        }
    }

    private fun buildJs(preset: UaPresets.Preset): String {
        val platformJs = JSONObject.quote(preset.navigatorPlatform ?: "")
        val ch = preset.clientHints

        val uaDataJs = if (ch == null) {
            // Real Firefox/Safari expose no navigator.userAgentData at all.
            """
            try {
                Object.defineProperty(navigator, 'userAgentData', { get: function () { return undefined; }, configurable: true });
            } catch (e) {}
            """.trimIndent()
        } else {
            // Build JSON arrays explicitly to avoid lambda/forEach inference problems.
            val brandsArray = JSONArray().apply {
                for (b in ch.brands) {
                    put(JSONObject().apply { put("brand", b.brand); put("version", b.version) })
                }
            }
            val fullVersionArray = JSONArray().apply {
                for (b in ch.brands) {
                    put(JSONObject().apply { put("brand", b.brand); put("version", ch.uaFullVersion) })
                }
            }
            """
            try {
                var __mlUaData = {
                    brands: $brandsArray,
                    mobile: ${ch.mobile},
                    platform: ${JSONObject.quote(ch.platform)},
                    toJSON: function () { return { brands: this.brands, mobile: this.mobile, platform: this.platform }; },
                    getHighEntropyValues: function (hints) {
                        var full = {
                            architecture: ${JSONObject.quote(ch.architecture)},
                            bitness: ${JSONObject.quote(ch.bitness)},
                            brands: this.brands,
                            fullVersionList: $fullVersionArray,
                            mobile: this.mobile,
                            model: ${JSONObject.quote(ch.model)},
                            platform: this.platform,
                            platformVersion: ${JSONObject.quote(ch.platformVersion)},
                            uaFullVersion: ${JSONObject.quote(ch.uaFullVersion)},
                            wow64: false
                        };
                        var result = {};
                        (hints || []).forEach(function (h) { if (h in full) result[h] = full[h]; });
                        return Promise.resolve(result);
                    }
                };
                Object.defineProperty(navigator, 'userAgentData', { get: function () { return __mlUaData; }, configurable: true });
            } catch (e) {}
            """.trimIndent()
        }

        val screenJs = if (preset.screenWidth != null && preset.screenHeight != null) {
            val w = preset.screenWidth
            val h = preset.screenHeight
            val dpr = preset.devicePixelRatio ?: 1.0
            """
            try {
                Object.defineProperty(window.screen, 'width', { get: function () { return $w; }, configurable: true });
                Object.defineProperty(window.screen, 'height', { get: function () { return $h; }, configurable: true });
                Object.defineProperty(window.screen, 'availWidth', { get: function () { return $w; }, configurable: true });
                Object.defineProperty(window.screen, 'availHeight', { get: function () { return $h; }, configurable: true });
                Object.defineProperty(window, 'devicePixelRatio', { get: function () { return $dpr; }, configurable: true });
            } catch (e) {}
            """.trimIndent()
        } else {
            ""
        }

        return """
        (function () {
            $uaDataJs
            $screenJs
            try {
                Object.defineProperty(navigator, 'platform', { get: function () { return $platformJs; }, configurable: true });
            } catch (e) {}
        })();
        """.trimIndent()
    }
}
