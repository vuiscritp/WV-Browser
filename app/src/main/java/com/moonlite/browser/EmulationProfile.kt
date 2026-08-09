package com.moonlite.browser


/**
 * Runtime-adjustable emulation on top of [UaPresets] — this is the part of
 * "faking a device" that Playwright exposes as context options
 * (`locale`, `timezoneId`, `geolocation`) rather than baking into a fixed
 * UA preset, because these vary per-*task*, not per-browser: two scrape
 * jobs both pretending to be "Chrome Android" can still need different
 * cities/timezones. Set via ControlServer's `/emulate` endpoint and applied
 * per tab; unset fields are left as WebView's real values.
 *
 * Everything here is best-effort JS-layer spoofing, not a kernel-level
 * override — a sufficiently determined fingerprinting script that probes
 * timing side channels or WebGL/audio rendering can still tell. What this
 * *does* reliably beat is the common, non-adversarial checks: reading
 * navigator.language(s), Intl.DateTimeFormat().resolvedOptions().timeZone,
 * Date.getTimezoneOffset(), navigator.hardwareConcurrency/deviceMemory, and
 * navigator.geolocation.
 */
data class EmulationOverrides(
    val locale: String? = null,          // e.g. "en-US"
    val timezoneId: String? = null,      // IANA id, e.g. "America/New_York"
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracy: Double? = null,
    val hardwareConcurrency: Int? = null,
    val deviceMemory: Int? = null
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("locale", locale ?: org.json.JSONObject.NULL)
        put("timezoneId", timezoneId ?: org.json.JSONObject.NULL)
        put("latitude", latitude ?: org.json.JSONObject.NULL)
        put("longitude", longitude ?: org.json.JSONObject.NULL)
        put("accuracy", accuracy ?: org.json.JSONObject.NULL)
        put("hardwareConcurrency", hardwareConcurrency ?: org.json.JSONObject.NULL)
        put("deviceMemory", deviceMemory ?: org.json.JSONObject.NULL)
    }
}

object EmulationProfile {

    /** Empty script when there's nothing to override — cheaper than branching at every call site. */
    fun buildJs(overrides: EmulationOverrides?): String {
        if (overrides == null) return ""
        val parts = mutableListOf<String>()

        overrides.locale?.let { locale ->
            val primary = locale.substringBefore('-')
            parts += """
                try {
                    Object.defineProperty(navigator, 'language', { get: function () { return ${org.json.JSONObject.quote(locale)}; }, configurable: true });
                    Object.defineProperty(navigator, 'languages', { get: function () { return [${org.json.JSONObject.quote(locale)}, ${org.json.JSONObject.quote(primary)}]; }, configurable: true });
                } catch (e) {}
            """.trimIndent()
        }

        overrides.timezoneId?.let { tz ->
            // Calculate the offset for the actual Date being queried, rather
            // than freezing today's offset. This preserves IANA DST changes
            // across historical/future dates.
            parts += """
                try {
                    var __mlTimeZone = ${org.json.JSONObject.quote(tz)};
                    var __mlOrigResolvedOptions = Intl.DateTimeFormat.prototype.resolvedOptions;
                    var __mlPartsToOffset = function (date) {
                        var fmt = new Intl.DateTimeFormat('en-US', {
                            timeZone: __mlTimeZone, year: 'numeric', month: '2-digit', day: '2-digit',
                            hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
                        });
                        var parts = fmt.formatToParts(date);
                        var values = {};
                        parts.forEach(function (p) { values[p.type] = p.value; });
                        var asUtc = Date.UTC(
                            Number(values.year), Number(values.month) - 1, Number(values.day),
                            Number(values.hour), Number(values.minute), Number(values.second)
                        );
                        return Math.round((asUtc - date.getTime()) / 60000);
                    };
                    Date.prototype.getTimezoneOffset = function () { return -__mlPartsToOffset(this); };
                    Intl.DateTimeFormat.prototype.resolvedOptions = function () {
                        var opts = __mlOrigResolvedOptions.apply(this, arguments);
                        opts.timeZone = __mlTimeZone;
                        return opts;
                    };
                } catch (e) {}
            """.trimIndent()
        }

        overrides.hardwareConcurrency?.let { hc ->
            parts += """
                try {
                    Object.defineProperty(navigator, 'hardwareConcurrency', { get: function () { return $hc; }, configurable: true });
                } catch (e) {}
            """.trimIndent()
        }

        overrides.deviceMemory?.let { dm ->
            parts += """
                try {
                    Object.defineProperty(navigator, 'deviceMemory', { get: function () { return $dm; }, configurable: true });
                } catch (e) {}
            """.trimIndent()
        }

        if (overrides.latitude != null && overrides.longitude != null) {
            val lat = overrides.latitude
            val lon = overrides.longitude
            val acc = overrides.accuracy ?: 20.0
            parts += """
                try {
                    var __mlPos = {
                        coords: {
                            latitude: $lat, longitude: $lon, accuracy: $acc,
                            altitude: null, altitudeAccuracy: null, heading: null, speed: null
                        },
                        timestamp: Date.now()
                    };
                    if (navigator.geolocation) {
                        navigator.geolocation.getCurrentPosition = function (success, error, options) {
                            if (success) setTimeout(function () { success(__mlPos); }, 0);
                        };
                        navigator.geolocation.watchPosition = function (success, error, options) {
                            if (success) setTimeout(function () { success(__mlPos); }, 0);
                            return 0;
                        };
                    }
                } catch (e) {}
            """.trimIndent()
        }

        if (parts.isEmpty()) return ""
        return "(function() {\n" + parts.joinToString("\n") + "\n})();"
    }
}
