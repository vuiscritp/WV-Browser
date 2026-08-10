package com.moonlite.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {
    private var service: MoonliteService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: android.os.IBinder?) {
            service = (binder as MoonliteService.LocalBinder).getService()
            bound = true
            refreshDynamicValues()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; bound = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ThemeManager.apply(findViewById(android.R.id.content), this)

        findViewById<android.widget.ImageButton>(R.id.settingsBack).setOnClickListener { finish() }
        val prefs = AppPrefs.edit(this)
        val compact = findViewById<SwitchCompat>(R.id.switchCompact)
        val animation = findViewById<SwitchCompat>(R.id.switchTabAnimation)
        val adblock = findViewById<SwitchCompat>(R.id.switchAdblock)
        compact.isChecked = AppPrefs.compactUiEnabled(this)
        animation.isChecked = AppPrefs.tabAnimationEnabled(this)
        adblock.isChecked = AppPrefs.adBlockEnabled(this)
        compact.setOnCheckedChangeListener { _, checked ->
            AppPrefs.edit(this).putBoolean("compact_ui", checked).apply()
            Toast.makeText(this, if (checked) "Compact toolbar enabled" else "Compact toolbar disabled", Toast.LENGTH_SHORT).show()
        }
        animation.setOnCheckedChangeListener { _, checked -> AppPrefs.edit(this).putBoolean("tab_animation", checked).apply() }
        adblock.setOnCheckedChangeListener { _, checked ->
            prefs.putBoolean("adblock_enabled", checked).apply()
            service?.tabManager?.activeWebView()?.reload()
        }

        findViewById<android.view.View>(R.id.rowSearch).setOnClickListener { chooseSearchEngine() }
        findViewById<android.view.View>(R.id.rowHomepage).setOnClickListener { chooseHomepage() }
        findViewById<android.view.View>(R.id.rowTheme).setOnClickListener { chooseTheme() }
        findViewById<android.view.View>(R.id.rowUiLanguage).setOnClickListener { chooseUiLanguage() }
        findViewById<android.view.View>(R.id.rowPersona).setOnClickListener { choosePersona() }
        findViewById<android.view.View>(R.id.rowLanguage).setOnClickListener { chooseBrowserLanguage() }
        findViewById<android.view.View>(R.id.rowTimezone).setOnClickListener { showTimezoneInfo() }
        findViewById<android.view.View>(R.id.rowClearData).setOnClickListener { clearBrowsingData() }
        findViewById<android.view.View>(R.id.rowApi).setOnClickListener { showToken() }
        findViewById<android.view.View>(R.id.rowToken).setOnClickListener { showToken() }
        findViewById<android.view.View>(R.id.rowDevtools).setOnClickListener { showDevtools() }
        findViewById<android.view.View>(R.id.rowNewTab).setOnClickListener {
            service?.tabManager?.newTab()
            Toast.makeText(this, "New tab opened", Toast.LENGTH_SHORT).show()
        }
        findViewById<android.view.View>(R.id.rowOverlay).setOnClickListener { requestOverlayPermission() }
        findViewById<android.view.View>(R.id.rowBackgroundAutomation).setOnClickListener { configureAutomation() }
        findViewById<android.view.View>(R.id.rowProxy).setOnClickListener { configureProxy() }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        refreshOverlayRow()
        service?.reparkInactiveTabs()
    }

    private fun requestOverlayPermission() {
        val host = service?.overlayHost
        if (host == null || host.isPermissionGranted()) {
            Toast.makeText(this, "Overlay permission is already granted", Toast.LENGTH_SHORT).show()
            return
        }
        overlayPermissionLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun refreshOverlayRow() {
        val granted = service?.overlayHost?.isPermissionGranted() ?: false
        findViewById<android.widget.TextView>(R.id.valueOverlay).text =
            if (granted) getString(R.string.overlay_granted) else getString(R.string.overlay_not_granted)
        findViewById<android.widget.TextView>(R.id.valueBackgroundAutomation).text =
            if (isAutomationEnabled()) "Automation: ON · tap to configure" else "Automation: OFF · tap to configure"
    }

    private fun isAutomationEnabled(): Boolean = getSharedPreferences("moonlite", MODE_PRIVATE).getBoolean("automation_enabled", false)

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MoonliteService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (service != null) refreshDynamicValues()
        refreshOverlayRow()
    }

    override fun onDestroy() {
        if (bound) { unbindService(connection); bound = false }
        super.onDestroy()
    }

    private fun refreshDynamicValues() {
        val svc = service ?: return
        findViewById<android.widget.TextView>(R.id.valuePersona).text = UaPresets.byId(svc.currentUaPresetId).label
        findViewById<android.widget.TextView>(R.id.valueSearch).text = SearchEngines.current().label
        findViewById<android.widget.TextView>(R.id.valueHomepage).text = SearchEngines.homepageLabel(this)
        findViewById<android.widget.TextView>(R.id.valueUiLanguage).text = if (UiLanguage.currentTag() == "vi") "Tiếng Việt" else "English"
        findViewById<android.widget.TextView>(R.id.valueTheme).text = ThemeManager.current(this).label
        refreshOverlayRow()
    }

    private fun chooseSearchEngine() {
        val labels = SearchEngines.ALL.map { it.label }.toTypedArray()
        val current = SearchEngines.ALL.indexOfFirst { it.id == SearchEngines.currentId }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Search engine").setSingleChoiceItems(labels, current) { dialog, which ->
            val engine = SearchEngines.ALL[which]
            SearchEngines.currentId = engine.id
            AppPrefs.edit(this).putString("search_engine", engine.id).apply()
            findViewById<android.widget.TextView>(R.id.valueSearch).text = engine.label
            dialog.dismiss()
        }.show()
    }

    private fun chooseHomepage() {
        val options = (SearchEngines.ALL.map { it.label } + "Custom URL").toTypedArray()
        AlertDialog.Builder(this).setTitle("Homepage").setItems(options) { _, which ->
            if (which < SearchEngines.ALL.size) {
                val engine = SearchEngines.ALL[which]
                AppPrefs.edit(this).putString("homepage", engine.homepage).apply()
                findViewById<android.widget.TextView>(R.id.valueHomepage).text = engine.label
            } else showCustomHomepage()
        }.show()
    }

    private fun showCustomHomepage() {
        val input = EditText(this).apply {
            isSingleLine = true
            hint = "https://example.com/"
            setText(getSharedPreferences("moonlite", MODE_PRIVATE).getString("homepage", ""))
        }
        AlertDialog.Builder(this).setTitle("Custom homepage").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                if (!value.startsWith("https://") && !value.startsWith("http://")) {
                    Toast.makeText(this, "Homepage must start with http:// or https://", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                AppPrefs.edit(this).putString("homepage", value).apply()
                findViewById<android.widget.TextView>(R.id.valueHomepage).text = "Custom"
            }.show()
    }

    private fun chooseTheme() {
        val labels = (ThemeManager.THEMES.map { it.label } + "Custom accent…").toTypedArray()
        val current = ThemeManager.THEMES.indexOfFirst { it.id == ThemeManager.current(this).id }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("UI theme").setSingleChoiceItems(labels, current) { dialog, which ->
            if (which < ThemeManager.THEMES.size) {
                ThemeManager.setTheme(this, ThemeManager.THEMES[which].id)
                dialog.dismiss(); recreate()
            } else {
                dialog.dismiss(); showCustomAccent()
            }
        }.show()
    }

    private fun showCustomAccent() {
        val input = EditText(this).apply { isSingleLine = true; hint = "#60A5FA"; setText("#60A5FA") }
        AlertDialog.Builder(this).setTitle("Custom accent").setMessage("Enter a hex color, for example #60A5FA.").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
                val value = input.text.toString().trim()
                if (runCatching { android.graphics.Color.parseColor(value) }.isFailure) {
                    Toast.makeText(this, "Invalid color", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                ThemeManager.setTheme(this, "midnight", value)
                recreate()
            }.show()
    }

    private fun chooseUiLanguage() {
        val labels = arrayOf("English", "Tiếng Việt")
        val current = if (UiLanguage.currentTag() == "vi") 1 else 0
        AlertDialog.Builder(this).setTitle("Interface language").setSingleChoiceItems(labels, current) { dialog, which ->
            UiLanguage.set(if (which == 1) "vi" else "en")
            dialog.dismiss()
            recreate()
        }.show()
    }

    private fun choosePersona() {
        val labels = UaPresets.ALL.map { it.label }.toTypedArray()
        val currentId = service?.currentUaPresetId ?: "moonlite_default"
        val current = UaPresets.ALL.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Browser persona").setSingleChoiceItems(labels, current) { dialog, which ->
            service?.setUaPreset(UaPresets.ALL[which].id)
            findViewById<android.widget.TextView>(R.id.valuePersona).text = UaPresets.ALL[which].label
            dialog.dismiss()
        }.show()
    }

    private fun chooseBrowserLanguage() {
        val languages = arrayOf("en-US", "vi-VN", "ja-JP", "ko-KR", "de-DE", "fr-FR")
        AlertDialog.Builder(this).setTitle("Browser language / locale").setItems(languages) { _, which ->
            val locale = languages[which]
            AppPrefs.edit(this).putString("locale_override", locale).apply()
            val tm = service?.tabManager
            val webView = tm?.activeWebView()
            if (tm != null && webView != null) {
                val merged = (tm.getEmulation(webView) ?: EmulationOverrides()).copy(locale = locale)
                tm.setEmulation(webView, merged)
            }
            Toast.makeText(this, "Browser locale: $locale", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun showTimezoneInfo() {
        AlertDialog.Builder(this).setTitle("Timezone")
            .setMessage("Timezone emulation is controlled by the browser profile / automation API. DST is calculated per date rather than hard-coded to one offset.")
            .setPositiveButton("OK", null).show()
    }

    private fun configureAutomation() {
        val enabled = isAutomationEnabled()
        val options = arrayOf("Enable background automation", "Request battery-optimization exemption", "Open overlay permission", "Disable background automation")
        AlertDialog.Builder(this).setTitle("Background automation").setMessage(
            "Keeps the foreground service and local control API available. Android may still stop an app after force-stop."
        ).setItems(options) { _, which ->
            when (which) {
                0 -> {
                    AppPrefs.edit(this).putBoolean("automation_enabled", true).apply()
                    Toast.makeText(this, "Background automation enabled", Toast.LENGTH_SHORT).show()
                }
                1 -> requestBatteryOptimizationExemption()
                2 -> requestOverlayPermission()
                3 -> {
                    AppPrefs.edit(this).putBoolean("automation_enabled", false).apply()
                    Toast.makeText(this, "Background automation disabled", Toast.LENGTH_SHORT).show()
                }
            }
            refreshOverlayRow()
        }.show()
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Battery optimization is already ignored", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun configureProxy() {
        AlertDialog.Builder(this).setTitle("Network proxy")
            .setMessage("1.1.1.1 is Cloudflare DNS, not an HTTP/SOCKS proxy. Use a real proxy host and port here. The WebView proxy applies app-wide.")
            .setPositiveButton("Set proxy") { _, _ -> showProxyEditor() }
            .setNegativeButton("Clear proxy") { _, _ -> clearProxy() }
            .setNeutralButton("Close", null).show()
    }

    private fun showProxyEditor() {
        val host = EditText(this).apply { hint = "Proxy host" }
        val port = EditText(this).apply { hint = "Port"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(32, 8, 32, 0); addView(host); addView(port) }
        AlertDialog.Builder(this).setTitle("Set app-wide proxy").setView(box).setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
            val h = host.text.toString().trim(); val p = port.text.toString().toIntOrNull() ?: 0
            if (h.isBlank() || p !in 1..65535) { Toast.makeText(this, "Invalid proxy host/port", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
            val ok = service?.setProxy(h, p, "http") ?: false
            Toast.makeText(this, if (ok) "App-wide proxy applied" else "This WebView does not support proxy override", Toast.LENGTH_LONG).show()
        }.show()
    }

    private fun clearProxy() {
        val ok = service?.clearProxy() ?: false
        Toast.makeText(this, if (ok) "Proxy cleared" else "Proxy override is unavailable", Toast.LENGTH_SHORT).show()
    }

    private fun clearBrowsingData() {
        AlertDialog.Builder(this).setTitle("Clear browsing data?")
            .setMessage("Clear cookies, Web Storage and WebView cache. This may sign you out of websites.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); WebStorage.getInstance().deleteAllData(); service?.tabManager?.tabsSnapshot()?.forEach { it.webView.clearCache(true) }; Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show() }
            }.show()
    }

    private fun showToken() {
        val token = AppPrefs.controlToken(this)
        AlertDialog.Builder(this).setTitle("MoonLite API token").setMessage(token)
            .setPositiveButton("Copy") { _, _ -> getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("MoonLite API token", token)); Toast.makeText(this, "API token copied", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("Close", null).show()
    }

    private fun showDevtools() {
        val text = if (BuildConfig.DEBUG) "Remote debugging is enabled in this debug build. Use chrome://inspect with ADB." else "Release build: remote debugging is disabled."
        AlertDialog.Builder(this).setTitle("Developer").setMessage(text).setPositiveButton("OK", null).show()
    }
}
