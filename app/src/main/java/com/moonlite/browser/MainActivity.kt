package com.moonlite.browser

import android.Manifest
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.Gravity
import android.widget.ImageButton
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

/**
 * Pure UI shell. The actual browser engine (tabs, control server, cookies)
 * lives in MoonliteService and keeps running whether or not this Activity
 * exists — this class just binds to it and borrows the active WebView to
 * display it. onStop() only detaches the view; nothing about the engine is
 * torn down when the user leaves or swipes the app away.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var tabStrip: LinearLayout
    private lateinit var addressBar: EditText
    private lateinit var webViewContainer: FrameLayout
    private lateinit var tabStripScroller: android.widget.HorizontalScrollView
    private lateinit var toolbarContainer: LinearLayout

    private var service: MoonliteService? = null
    private var bound = false

    private val uiListener: () -> Unit = {
        runOnUiThread {
            renderTabStrip()
            // The first WebView is created asynchronously by MoonliteService
            // so startup cannot be blocked by Chromium initialization. Attach
            // it as soon as the service reports that the tab exists.
            tabManager()?.attachActiveTo(webViewContainer)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* granted or not, nothing else to do */ }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as MoonliteService.LocalBinder).getService()
            service = svc
            bound = true
            svc.addUiListener(uiListener)
            renderTabStrip()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private fun tabManager() = service?.tabManager

    override fun onCreate(savedInstanceState: Bundle?) {
        StartupLog.mark("MAIN:onCreate:start")
        try {
            super.onCreate(savedInstanceState)
            StartupLog.mark("MAIN:super.onCreate:ok")
            setContentView(R.layout.activity_main)
            ThemeManager.apply(findViewById(android.R.id.content), this)
            StartupLog.mark("MAIN:setContentView:ok")

        drawerLayout = findViewById(R.id.drawerLayout)
        tabStrip = findViewById(R.id.tabStrip)
        addressBar = findViewById(R.id.addressBar)
        webViewContainer = findViewById(R.id.webViewContainer)
        tabStripScroller = findViewById(R.id.tabStripScroller)
        toolbarContainer = findViewById(R.id.toolbarContainer)

        android.webkit.WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        requestNotificationPermissionIfNeeded()

        // Do not launch the battery-optimization Settings activity during startup.
        // Some ColorOS builds treat this as an unsolicited background transition
        // and can interrupt the foreground-service startup sequence. The user can
        // still grant the exemption manually from Settings.
        setupToolbar()
        setupDrawer()
        setupBackHandling()
        StartupLog.mark("MAIN:ui_setup:ok")

        val serviceIntent = Intent(this, MoonliteService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            StartupLog.mark("MAIN:startForegroundService:ok")
        } catch (t: Throwable) {
            StartupLog.crashPoint("MAIN:startForegroundService", t)
            throw t
        }
        try {
            bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            StartupLog.mark("MAIN:bindService:ok")
        } catch (t: Throwable) {
            StartupLog.crashPoint("MAIN:bindService", t)
            throw t
        }
        } catch (t: Throwable) {
            StartupLog.crashPoint("MAIN:onCreate", t)
            throw t
        }
    }

    /**
     * Without this, the system back button/gesture has no in-app history to
     * fall back on, so AppCompatActivity's default behavior just finishes
     * the Activity — which looks and feels exactly like the app quitting,
     * even though MoonliteService keeps running headless underneath. This
     * intercepts back and, in order: closes the drawer if it's open, then
     * goes back in the active tab's web history, then (only when there's
     * nowhere left to go) backgrounds the app instead of destroying it —
     * matching how every mainstream mobile browser handles back.
     */
    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val webView = tabManager()?.activeWebView()
                    when {
                        drawerLayout.isDrawerOpen(GravityCompat.START) -> drawerLayout.closeDrawers()
                        webView != null && webView.canGoBack() -> webView.goBack()
                        else -> moveTaskToBack(true)
                    }
                }
            }
        )
    }

    override fun onStart() {
        super.onStart()
        applyUiDensity()
        tabManager()?.attachActiveTo(webViewContainer)
    }

    override fun onStop() {
        // Only detach the view — the service, its tabs, and the control
        // server keep running headless. Nothing is destroyed here.
        tabManager()?.detachFrom(webViewContainer)
        super.onStop()
    }

    override fun onDestroy() {
        if (bound) {
            service?.removeUiListener(uiListener)
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Without this, many OEM ROMs (MIUI, ColorOS, FuntouchOS, etc. — common
     * on Vietnamese devices) will kill the background service anyway despite
     * stopWithTask="false" and a foreground notification, because their own
     * battery managers ignore Android's normal rules. This is the one
     * user-facing permission that actually matters for real background
     * persistence; nothing in code can substitute for it.
     */
    private fun suggestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (e: Exception) {
                // Some OEM ROMs block this intent outright; user can still
                // enable it manually from Settings > Battery > MoonLite.
            }
        }
    }

    private fun applyUiDensity() {
        val compact = AppPrefs.compactUiEnabled(this)
        val tabParams = tabStripScroller.layoutParams
        tabParams.height = dp(if (compact) 34 else 40)
        tabStripScroller.layoutParams = tabParams
        val toolbarParams = toolbarContainer.layoutParams
        toolbarParams.height = dp(if (compact) 44 else 48)
        toolbarContainer.layoutParams = toolbarParams
    }

    private fun setupToolbar() {
        findViewById<ImageButton>(R.id.menuButton).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<ImageButton>(R.id.refreshButton).setOnClickListener {
            tabManager()?.activeWebView()?.reload()
        }

        findViewById<ImageButton>(R.id.moreButton).setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor, Gravity.END)
            popup.menu.add("Tab mới")
            popup.menu.add("Làm mới")
            popup.menu.add("Tab ẩn danh")
            popup.menu.add("Cài đặt")
            popup.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Tab mới" -> { tabManager()?.newTab(); true }
                    "Làm mới" -> { tabManager()?.activeWebView()?.reload(); true }
                    "Tab ẩn danh" -> { tabManager()?.newTab(incognito = true); true }
                    "Cài đặt" -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                    else -> false
                }
            }
            popup.show()
        }

        addressBar.setOnFocusChangeListener { _, focused ->
            if (focused) {
                addressBar.selectAll()
                findViewById<android.view.View>(R.id.addressShell).animate().scaleX(1.01f).scaleY(1.01f).setDuration(140).start()
            } else {
                findViewById<android.view.View>(R.id.addressShell).animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
        }

        val goLoad = {
            val input = addressBar.text.toString().trim()
            val webView = tabManager()?.activeWebView()
            if (input.isNotEmpty() && webView != null) {
                BrowserActions.load(webView, input)
                addressBar.clearFocus()
            }
        }
        addressBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                goLoad(); true
            } else false
        }
    }

    private fun setupDrawer() {
        refreshDrawerLabels()

        findViewById<TextView>(R.id.menuSettings).setOnClickListener {
            drawerLayout.closeDrawers()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.menuDesktopMode).setOnClickListener {
            val newState = !AppPrefs.desktopModeEnabled(this)
            AppPrefs.edit(this).putBoolean("desktop_mode", newState).apply()
            service?.setUaPreset(if (newState) "chrome_desktop" else "moonlite_default")
            refreshDrawerLabels()
            drawerLayout.closeDrawers()
        }

        findViewById<TextView>(R.id.menuUa).setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            UaPresets.ALL.forEachIndexed { index, preset -> popup.menu.add(0, index, index, preset.label) }
            popup.setOnMenuItemClickListener { item ->
                val preset = UaPresets.ALL[item.itemId]
                service?.setUaPreset(preset.id)
                Toast.makeText(this, "UA: ${preset.label}", Toast.LENGTH_SHORT).show()
                drawerLayout.closeDrawers()
                true
            }
            popup.show()
        }

        findViewById<TextView>(R.id.menuSearchEngine).setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            SearchEngines.ALL.forEachIndexed { index, engine -> popup.menu.add(0, index, index, engine.label) }
            popup.setOnMenuItemClickListener { item ->
                val engine = SearchEngines.ALL[item.itemId]
                SearchEngines.currentId = engine.id
                AppPrefs.edit(this).putString("search_engine", engine.id).apply()
                refreshDrawerLabels()
                Toast.makeText(this, "Tìm kiếm: ${engine.label}", Toast.LENGTH_SHORT).show()
                drawerLayout.closeDrawers()
                true
            }
            popup.show()
        }

        findViewById<TextView>(R.id.menuTranslateNow).setOnClickListener {
            val svc = service
            val webView = tabManager()?.activeWebView()
            if (svc != null && webView != null) {
                val target = AppPrefs.targetLang(this)
                Toast.makeText(this, "Đang dịch sang '$target'...", Toast.LENGTH_SHORT).show()
                svc.translateManager.translatePage(webView, target) {
                    Toast.makeText(this, "Đã dịch xong", Toast.LENGTH_SHORT).show()
                }
            }
            drawerLayout.closeDrawers()
        }

        findViewById<TextView>(R.id.menuAutoTranslate).setOnClickListener {
            val newState = !AppPrefs.autoTranslateEnabled(this)
            AppPrefs.edit(this).putBoolean("auto_translate", newState).apply()
            refreshDrawerLabels()
            Toast.makeText(
                this,
                if (newState) "Auto-dịch: BẬT (${AppPrefs.targetLang(this)})" else "Auto-dịch: TẮT",
                Toast.LENGTH_SHORT
            ).show()
            drawerLayout.closeDrawers()
        }

        findViewById<TextView>(R.id.menuAdblock).setOnClickListener {
            val newState = !AppPrefs.adBlockEnabled(this)
            AppPrefs.edit(this).putBoolean("adblock_enabled", newState).apply()
            refreshDrawerLabels()
            Toast.makeText(this, if (newState) "Chặn quảng cáo: BẬT" else "Chặn quảng cáo: TẮT", Toast.LENGTH_SHORT).show()
            tabManager()?.activeWebView()?.reload()
            drawerLayout.closeDrawers()
        }

        findViewById<TextView>(R.id.menuApiToken).setOnClickListener {
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

        findViewById<TextView>(R.id.menuDevTools).setOnClickListener {
            val message = if (BuildConfig.DEBUG) {
                "Remote debugging đang bật. Mở chrome://inspect trên Chrome desktop, kết nối máy qua USB debugging."
            } else {
                "Remote debugging chỉ được bật trong bản debug."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            drawerLayout.closeDrawers()
        }
    }

    private fun refreshDrawerLabels() {
        setStatusLabel(R.id.menuDesktopMode, "Chế độ Desktop", AppPrefs.desktopModeEnabled(this))
        setStatusLabel(R.id.menuAutoTranslate, "Auto-dịch", AppPrefs.autoTranslateEnabled(this))
        setStatusLabel(R.id.menuAdblock, "Chặn quảng cáo", AppPrefs.adBlockEnabled(this))
        findViewById<TextView>(R.id.menuSearchEngine).text =
            "Công cụ tìm kiếm: ${SearchEngines.current().label}"
    }

    /** Sets "<label>: BẬT/TẮT" with just the BẬT/TẮT word colored green/red — state is readable at a glance, not just by text. */
    private fun setStatusLabel(viewId: Int, label: String, on: Boolean) {
        val stateWord = if (on) "BẬT" else "TẮT"
        val full = "$label: $stateWord"
        val span = SpannableString(full)
        val color = ContextCompat.getColor(this, if (on) R.color.state_on else R.color.state_off)
        span.setSpan(
            ForegroundColorSpan(color),
            full.length - stateWord.length,
            full.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        findViewById<TextView>(viewId).text = span
    }

    private fun bindScrollChrome(webView: android.webkit.WebView) {
        var lastY = webView.scrollY
        webView.setOnScrollChangeListener { _, scrollY, _, _, _ ->
            val dy = scrollY - lastY
            lastY = scrollY
            if (scrollY <= 8 || dy < -10) {
                if (tabStripScroller.visibility != View.VISIBLE) {
                    tabStripScroller.visibility = View.VISIBLE
                    tabStripScroller.alpha = 0f
                    tabStripScroller.translationY = -8f
                    tabStripScroller.animate().alpha(1f).translationY(0f).setDuration(180).start()
                }
            } else if (dy > 10 && tabStripScroller.visibility == View.VISIBLE) {
                tabStripScroller.animate().alpha(0f).translationY(-8f).setDuration(150).withEndAction {
                    tabStripScroller.visibility = View.GONE
                }.start()
            }
        }
    }

    private fun renderTabStrip() {
        val tm = tabManager() ?: return
        tabStrip.removeAllViews()
        val tabs = tm.tabsSnapshot()
        val activeWebView = tm.activeWebView()
        val animateTabs = AppPrefs.tabAnimationEnabled(this)

        tabs.forEachIndexed { index, tab ->
            val chip = LayoutInflater.from(this).inflate(R.layout.tab_item, tabStrip, false)
            val titleView = chip.findViewById<TextView>(R.id.tabTitle)
            val closeView = chip.findViewById<ImageButton>(R.id.tabClose)

            titleView.text = tab.title.ifBlank { "New Tab" }
            chip.setBackgroundResource(
                if (tab.webView === activeWebView) R.drawable.tab_chip_active_bg else R.drawable.tab_chip_bg
            )
            chip.setOnClickListener {
                tm.switchTo(index)
                addressBar.setText(tab.webView.url ?: "")
            }
            closeView.setOnClickListener { tm.closeTab(index) }

            if (animateTabs) {
                chip.alpha = 0f
                chip.scaleX = 0.96f
                chip.scaleY = 0.96f
                chip.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
            }
            tabStrip.addView(chip)
        }

        val plus = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { gravity = Gravity.CENTER_VERTICAL }
            background = getDrawable(R.drawable.toolbar_icon_bg)
            setImageResource(R.drawable.ic_plus)
            imageTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.text_white)
            contentDescription = "New tab"
            setPadding(dp(9), dp(9), dp(9), dp(9))
            setOnClickListener { tm.newTab() }
        }
        tabStrip.addView(plus)

        tm.attachActiveTo(webViewContainer)
        tm.activeWebView()?.let { webView ->
            webView.url?.let { addressBar.setText(it) }
            bindScrollChrome(webView)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

}
