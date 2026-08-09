package com.moonlite.browser

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 * input); (3) write results back into the tagged elements via JS.
 *
 * Earlier version joined every element's text with "\n" into ONE request
 * and split the translated result back apart the same way — but Google's
 * endpoint re-segments by its own sentence boundaries, not by the newlines
 * sent in, so the split-back-apart line count didn't reliably match the
 * original and text drifted onto the wrong elements. It was also
 * all-or-nothing: one failed request meant nothing on the page translated.
 * Per-text requests (below) fix both: each result maps unambiguously to
 * exactly the text that produced it, and one failed request only drops
 * that one piece of text, not the whole page.
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

            val resultByIdx = ConcurrentHashMap<Int, String>()
            val latch = CountDownLatch(indicesByText.size)
            for ((text, idxList) in indicesByText) {
                executor.submit {
                    try {
                        val translated = translateText(text, targetLang)
                        idxList.forEach { idx -> resultByIdx[idx] = translated }
                    } catch (e: Exception) {
                        // Leave these indices untranslated — every other
                        // text's own request still lands independently.
                    } finally {
                        latch.countDown()
                    }
                }
            }

            // Bounded wait off the main thread; whatever's finished by
            // then gets applied — a slow/failed handful of requests
            // shouldn't hold back everything else that already succeeded.
            executor.submit {
                latch.await(PAGE_TRANSLATE_TIMEOUT_SEC, TimeUnit.SECONDS)
                mainHandler.post {
                    if (resultByIdx.isNotEmpty()) applyTranslation(webView, resultByIdx)
                    onDone?.invoke()
                }
            }
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

    private fun applyTranslation(webView: WebView, resultByIdx: Map<Int, String>) {
        val obj = org.json.JSONObject()
        resultByIdx.forEach { (idx, text) -> obj.put(idx.toString(), text) }
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
        private const val PAGE_TRANSLATE_TIMEOUT_SEC = 15L
    }
}
