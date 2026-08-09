package com.moonlite.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * A very small Tampermonkey-style userscript engine. Scripts are matched
 * against the loaded URL by a simple substring/wildcard pattern and injected
 * as JS (optionally CSS wrapped in a <style> tag) once the page finishes
 * loading. This is NOT a real extension system: no background scripts, no
 * cross-origin privileges beyond what the page's own JS context has.
 */
data class UserScript(
    val name: String,
    val matchPattern: String, // e.g. "*.example.com/*" or plain substring
    val code: String,
    val isCss: Boolean = false,
    var enabled: Boolean = true
)

class UserScriptManager {

    private val scripts = mutableListOf<UserScript>()

    fun add(script: UserScript) {
        scripts.removeAll { it.name == script.name }
        scripts.add(script)
    }

    fun remove(name: String) {
        scripts.removeAll { it.name == name }
    }

    fun list(): List<UserScript> = scripts.toList()

    fun toJson(): JSONArray {
        val arr = JSONArray()
        scripts.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("match", it.matchPattern)
                    .put("isCss", it.isCss)
                    .put("enabled", it.enabled)
            )
        }
        return arr
    }

    /** Returns the combined JS to evaluate for the given URL, or null if nothing matches. */
    fun buildInjectionFor(url: String?): String? {
        if (url == null) return null
        val matching = scripts.filter { it.enabled && matches(it.matchPattern, url) }
        if (matching.isEmpty()) return null

        val builder = StringBuilder()
        for (script in matching) {
            if (script.isCss) {
                val escaped = script.code.replace("`", "\\`")
                builder.append(
                    """
                    (function(){
                        var s = document.createElement('style');
                        s.textContent = `$escaped`;
                        document.documentElement.appendChild(s);
                    })();
                    """.trimIndent()
                )
            } else {
                builder.append("(function(){ try { ${script.code} } catch(e) {} })();")
            }
            builder.append("\n")
        }
        return builder.toString()
    }

    /** Simple pattern match: '*' as wildcard, otherwise plain substring match. */
    private fun matches(pattern: String, url: String): Boolean {
        if (pattern == "*" || pattern.isBlank()) return true
        if (!pattern.contains("*")) return url.contains(pattern)
        val regex = Regex(
            Regex.escape(pattern).replace("\\*", ".*")
        )
        return regex.containsMatchIn(url)
    }
}
