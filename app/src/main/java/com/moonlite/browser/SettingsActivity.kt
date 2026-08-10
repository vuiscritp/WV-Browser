package com.moonlite.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {
    private var service: MoonliteService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as MoonliteService.LocalBinder).getService()
            bound = true
            refreshDynamicValues()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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
            Toast.makeText(this, if (checked) "Giao diện gọn đã bật" else "Giao diện gọn đã tắt", Toast.LENGTH_SHORT).show()
        }
        animation.setOnCheckedChangeListener { _, checked ->
            AppPrefs.edit(this).putBoolean("tab_animation", checked).apply()
        }
        adblock.setOnCheckedChangeListener { _, checked ->
            prefs.putBoolean("adblock_enabled", checked).apply()
            service?.tabManager?.activeWebView()?.reload()
        }

        findViewById<android.view.View>(R.id.rowSearch).setOnClickListener { chooseSearchEngine() }
        findViewById<android.view.View>(R.id.rowHomepage).setOnClickListener { chooseHomepage() }
        findViewById<android.view.View>(R.id.rowTheme).setOnClickListener { showThemeInfo() }
        findViewById<android.view.View>(R.id.rowPersona).setOnClickListener { choosePersona() }
        findViewById<android.view.View>(R.id.rowLanguage).setOnClickListener { chooseLanguage() }
        findViewById<android.view.View>(R.id.rowTimezone).setOnClickListener { showTimezoneInfo() }
        findViewById<android.view.View>(R.id.rowClearData).setOnClickListener { clearBrowsingData() }
        findViewById<android.view.View>(R.id.rowApi).setOnClickListener { showToken() }
        findViewById<android.view.View>(R.id.rowToken).setOnClickListener { showToken() }
        findViewById<android.view.View>(R.id.rowDevtools).setOnClickListener { showDevtools() }
        findViewById<android.view.View>(R.id.rowNewTab).setOnClickListener {
            service?.tabManager?.newTab()
            Toast.makeText(this, "Đã mở tab mới", Toast.LENGTH_SHORT).show()
        }
        findViewById<android.view.View>(R.id.rowOverlay).setOnClickListener { requestOverlayPermission() }
    }

    private val overlayPermissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
            // The overlay permission has no direct "granted/denied" result
            // extra — it's a Settings screen, not a permission dialog — so
            // this just re-checks the actual state once the user is back.
            refreshOverlayRow()
            service?.reparkInactiveTabs()
        }

    private fun requestOverlayPermission() {
        val host = service?.overlayHost
        if (host == null || host.isPermissionGranted()) {
            Toast.makeText(this, "Đã cấp quyền — tab nền sẽ tiếp tục render khi đóng app", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun refreshOverlayRow() {
        val granted = service?.overlayHost?.isPermissionGranted() ?: false
        findViewById<android.widget.TextView>(R.id.valueOverlay).text =
            if (granted) "Đã cấp quyền" else "Chưa cấp quyền — bấm để bật"
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, MoonliteService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun refreshDynamicValues() {
        val svc = service ?: return
        val persona = UaPresets.byId(svc.currentUaPresetId).label
        findViewById<android.widget.TextView>(R.id.valuePersona).text = persona
        findViewById<android.widget.TextView>(R.id.valueSearch).text = SearchEngines.current().label
        refreshOverlayRow()
    }

    private fun chooseSearchEngine() {
        val labels = SearchEngines.ALL.map { it.label }.toTypedArray()
        val current = SearchEngines.ALL.indexOfFirst { it.id == SearchEngines.currentId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Công cụ tìm kiếm")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val engine = SearchEngines.ALL[which]
                SearchEngines.currentId = engine.id
                AppPrefs.edit(this).putString("search_engine", engine.id).apply()
                findViewById<android.widget.TextView>(R.id.valueSearch).text = engine.label
                dialog.dismiss()
            }.show()
    }

    private fun chooseHomepage() {
        val options = arrayOf("IANA Example Domains", "Blank / about:blank")
        AlertDialog.Builder(this)
            .setTitle("Trang chủ")
            .setItems(options) { _, which ->
                val value = if (which == 0) "https://www.iana.org/help/example-domains" else "about:blank"
                AppPrefs.edit(this).putString("homepage", value).apply()
                findViewById<android.widget.TextView>(R.id.valueHomepage).text = options[which]
            }.show()
    }

    private fun showThemeInfo() {
        AlertDialog.Builder(this)
            .setTitle("Giao diện")
            .setMessage("MoonLite hiện dùng giao diện tối tối ưu cho WebView. Các thành phần native dùng màu vector/drawable để giữ UI ổn định và nhẹ.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun choosePersona() {
        val labels = UaPresets.ALL.map { it.label }.toTypedArray()
        val currentId = service?.currentUaPresetId ?: "moonlite_default"
        val current = UaPresets.ALL.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Browser persona")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                service?.setUaPreset(UaPresets.ALL[which].id)
                findViewById<android.widget.TextView>(R.id.valuePersona).text = UaPresets.ALL[which].label
                dialog.dismiss()
            }.show()
    }

    private fun chooseLanguage() {
        val languages = arrayOf("vi-VN", "en-US", "ja-JP", "ko-KR", "de-DE", "fr-FR")
        AlertDialog.Builder(this)
            .setTitle("Language / locale")
            .setItems(languages) { _, which ->
                val locale = languages[which]
                AppPrefs.edit(this).putString("locale_override", locale).apply()
                // Applies to the tab that's open right now too — without
                // this, the setting only ever took effect for tabs opened
                // *after* the change, which looks like it silently did
                // nothing on whatever page you were already looking at.
                val tm = service?.tabManager
                val webView = tm?.activeWebView()
                if (tm != null && webView != null) {
                    val merged = (tm.getEmulation(webView) ?: EmulationOverrides()).copy(locale = locale)
                    tm.setEmulation(webView, merged)
                }
                Toast.makeText(this, "Locale profile: $locale", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun showTimezoneInfo() {
        AlertDialog.Builder(this)
            .setTitle("Timezone")
            .setMessage("Timezone emulation được điều khiển theo browser persona/control API. DST được tính theo ngày thay vì hard-code một offset duy nhất.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun clearBrowsingData() {
        AlertDialog.Builder(this)
            .setTitle("Xóa dữ liệu duyệt web?")
            .setMessage("Xóa cookie, Web Storage và cache của WebView hiện tại. Thao tác này có thể đăng xuất các website.")
            .setNegativeButton("Hủy", null)
            .setPositiveButton("Xóa") { _, _ ->
                val cm = CookieManager.getInstance()
                cm.removeAllCookies {
                    cm.flush()
                    WebStorage.getInstance().deleteAllData()
                    service?.tabManager?.tabsSnapshot()?.forEach { it.webView.clearCache(true) }
                    Toast.makeText(this, "Đã xóa dữ liệu WebView", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }

    private fun showToken() {
        val token = AppPrefs.controlToken(this)
        AlertDialog.Builder(this)
            .setTitle("MoonLite API token")
            .setMessage(token)
            .setPositiveButton("Sao chép") { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("MoonLite API token", token))
                Toast.makeText(this, "Đã sao chép API token", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()
    }

    private fun showDevtools() {
        val text = if (BuildConfig.DEBUG) {
            "Remote debugging đang bật trong debug build. Có thể dùng chrome://inspect khi thiết bị được kết nối ADB."
        } else {
            "Release build: remote debugging bị tắt để tránh lộ WebView contents."
        }
        AlertDialog.Builder(this).setTitle("Developer").setMessage(text).setPositiveButton("OK", null).show()
    }
}
