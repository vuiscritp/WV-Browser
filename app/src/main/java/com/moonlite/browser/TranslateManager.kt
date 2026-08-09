package com.moonlite.browser

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Free, unofficial Google Translate client using the same "gtx" endpoint that
 * browser extensions and tools like `googletrans` use — no API key needed,
 * but it is a reverse-engineered endpoint, not an official API. It can be
 * rate-limited or changed by Google without notice.
 *
 * Page translation: (1) collect text-bearing elements via JS, tagging each
 * with a data attribute; (2) translate each *distinct* text once (many
 * elements on a page repeat — nav labels, footer links — so this both cuts
 * request count and removes any ambiguity about which output maps to which
 * input); (3) write each result back into its element(s) the moment it
 * resolves, not all at once at the end — a page visibly fills in
 * progressively instead of sitting untranslated for several seconds and
 * then jumping all at once (or timing out with nothing at all).
 *
 * The previous version's real bug: `HttpURLConnection` was left with Java's
 * own default User-Agent ("Java/1.8.0...") on every request. Google's `gtx`
 * endpoint is known to rate-limit/block that default UA hard and fast —
 * under real page-translate load (dozens of requests in a burst from one
 * IP), most of them came back HTTP 403, and those were being silently
 * swallowed by a blanket catch — so only the lucky first few requests
 * before the block kicked in ever got translated. Sending a normal
 * browser-shaped User-Agent (below) is the actual, documented fix other
 * gtx-endpoint clients (py-googletrans etc.) use for exactly this failure
 * mode.
 */
class TranslateManager {

    // Small bounded pool, not one thread per element — a page can have up
    // to MAX_ELEMENTS distinct texts, and firing that many requests at once
    // both risks Google's rate limiting and doesn't meaningfully speed
    // things up past a handful of concurrent connections.
    private val executor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val extractScript = """
        (function() {
            var nodes = document.querySelectorAll('body *');
            var results = [];
            var idx = 0;
            for (var i = 0; i < nodes.length && idx < $MAX_ELEMENTS; i++) {
                var el = nodes[i];
                if (['SCRIPT','STYLE','NOSCRIPT','TEXTAREA','INPUT'].indexOf(el.tagName) !== -1) continue;
                var hasDirectText = false;
                for (var c = 0; c < el.childNodes.length; c++) {
                    var n = el.childNodes[c];
                    if (n.nodeType === 3 && n.textContent.trim().length > 1) { hasDirectText = true; break; }
                }
                if (!hasDirectText) continue;
                el.setAttribute('data-mlidx', idx);
                results.push({idx: idx, text: el.textContent.trim().substring(0, 400)});
                idx++;
            }
            return JSON.stringify(results);
        })();
    """.trimIndent()

    fun translatePage(webView: WebView, targetLang: String, onDone: (() -> Unit)? = null) {
        webView.evaluateJavascript(extractScript) { rawJson ->
            val items = try {
                JSONArray(unescapeJsString(rawJson))
            } catch (e: Exception) {
                onDone?.invoke(); return@evaluateJavascript
            }
            if (items.length() == 0) {
                onDone?.invoke(); return@evaluateJavascript
            }

            // text -> every element index that has exactly this text, so a
            // repeated string (menu items, footer boilerplate) is only sent
            // to Google once no matter how many elements share it.
            val indicesByText = LinkedHashMap<String, MutableList<Int>>()
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                val idx = obj.getInt("idx")
                val text = obj.getString("text")
                if (text.isBlank()) continue
                indicesByText.getOrPut(text) { mutableListOf() }.add(idx)
            }
            if (indicesByText.isEmpty()) {
                onDone?.invoke(); return@evaluateJavascript
            }

            val remaining = AtomicInteger(indicesByText.size)
            val done = {
                if (remaining.decrementAndGet() == 0) onDone?.invoke()
            }

            // Staggered dispatch, not all N submitted in the same instant —
            // even with only 4 worker threads, queuing every task at once
            // means thread 1 fires request #1, #5, #9... back-to-back with
            // zero gap, which is exactly the burst pattern that trips
            // rate-limiting. A small stagger spreads the burst out.
            var delayMs = 0L
            for ((text, idxList) in indicesByText) {
                executor.submit {
                    if (delayMs > 0) Thread.sleep(delayMs)
                    try {
                        val translated = translateTextWithRetry(text, targetLang)
                        mainHandler.post {
                            applyTranslation(webView, idxList, translated)
                            done()
                        }
                    } catch (e: Exception) {
                        // This one text stays untranslated; every other
                        // text's own request still lands independently —
                        // no all-or-nothing failure.
                        mainHandler.post { done() }
                    }
                }
                delayMs += STAGGER_MS
            }
        }
    }

    /** One retry with backoff — covers the occasional transient 429/timeout without hammering on a real block. */
    private fun translateTextWithRetry(text: String, targetLang: String): String {
        return try {
            translateText(text, targetLang)
        } catch (e: Exception) {
            Thread.sleep(400)
            translateText(text, targetLang)
        }
    }

    /** Translates a single string (blocking network call) via the free gtx endpoint. */
    fun translateText(text: String, targetLang: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val url = URL(
            "https://translate.googleapis.com/translate_a/single" +
                "?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "GET"
        // The actual fix: without a browser-shaped User-Agent, Google's
        // abuse detection on this endpoint blocks most requests in a burst
        // almost immediately (HTTP 403). Accept-Language matching the
        // target only nudges response formatting; it isn't load-bearing
        // the way User-Agent is.
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        )
        conn.setRequestProperty("Accept-Language", "$targetLang,en;q=0.8")
        conn.setRequestProperty("Referer", "https://translate.google.com/")

        val code = conn.responseCode
        if (code != 200) {
            conn.disconnect()
            throw java.io.IOException("gtx endpoint returned HTTP $code")
        }
        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val outer = JSONArray(body)
        val segments = outer.getJSONArray(0)
        val sb = StringBuilder()
        for (i in 0 until segments.length()) {
            sb.append(segments.getJSONArray(i).getString(0))
        }
        return sb.toString()
    }

    private fun applyTranslation(webView: WebView, indices: List<Int>, translated: String) {
        val obj = org.json.JSONObject()
        indices.forEach { idx -> obj.put(idx.toString(), translated) }
        val script = """
            (function() {
                var map = $obj;
                for (var key in map) {
                    var el = document.querySelector('[data-mlidx="' + key + '"]');
                    if (el) el.textContent = map[key];
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    /** evaluateJavascript's callback gives a JSON-encoded string; unwrap the outer quoting. */
    private fun unescapeJsString(raw: String?): String {
        if (raw == null) return "[]"
        return try {
            org.json.JSONTokener(raw).nextValue().toString()
        } catch (e: Exception) {
            raw
        }
    }

    companion object {
        private const val MAX_ELEMENTS = 220
        private const val STAGGER_MS = 60L
    }
}
