package com.moonlite.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import androidx.core.app.NotificationCompat

/**
 * Owns the browser engine (tabs + control server) independently of any
 * Activity. Declared in the manifest with android:stopWithTask="false", so
 * swiping MoonLite away from Recents does NOT kill this service — the
 * control API on 127.0.0.1:8848 and any open tabs keep running headless.
 * MainActivity just binds here and borrows the active WebView to display it;
 * closing the Activity only detaches the view (see TabManager.detachFrom),
 * it never tears the engine down.
 *
 * Honest limit: if the whole process gets force-killed (user taps "Force
 * stop", or an aggressive OEM battery manager kills it outright), no
 * incoming network request can bring it back — that's an Android OS
 * security boundary, no app can route around it from the outside.
 * START_STICKY tells the system to restart this service after a low-memory
 * kill, which is the best available safety net; it does not survive a
 * deliberate force-stop.
 */
class MoonliteService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): MoonliteService = this@MoonliteService
    }

    private val binder = LocalBinder()

    lateinit var tabManager: TabManager
        private set
    val userScriptManager = UserScriptManager()
    val translateManager = TranslateManager()
    var currentUaPresetId: String = "moonlite_default"
        private set

    private var controlServer: ControlServer? = null
    private val uiListeners = mutableSetOf<() -> Unit>()
    private val serviceStartTime: Long = System.currentTimeMillis()

    override fun onCreate() {
        // Must run before any WebView is constructed anywhere in the
        // process (the very first tab is created a few lines below via
        // tabManager.newTab). Without it, WebView splits long pages into
        // tiles and only draws the ones currently on screen — fine for a
        // simple article, but it means a JS-heavy page (long canvas/WASM
        // content, virtualized lists that render everything at once,
        // headless scraping that reads the whole DOM) can have stale or
        // blank regions outside the visible viewport. This trades a little
        // extra drawing cost for the whole document always being current.
        android.webkit.WebView.enableSlowWholeDocumentDraw()
        super.onCreate()
        android.webkit.WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        startForeground(NOTIFICATION_ID, buildNotification("Đang khởi động…"))

        if (AppPrefs.desktopModeEnabled(this)) currentUaPresetId = "chrome_desktop"
        SearchEngines.currentId = AppPrefs.searchEngineId(this)

        // WebView needs a themed context; a bare application Context throws
        // on some WebView builds. This gets reused for every tab's WebView.
        val webViewContext = ContextThemeWrapper(applicationContext, R.style.Theme_AppCompat_DayNight_NoActionBar)
        tabManager = TabManager(
            context = webViewContext,
            userScriptManager = userScriptManager,
            currentPresetProvider = { UaPresets.byId(currentUaPresetId) },
            onTabsChanged = { notifyUi() },
            onActiveTitleChanged = { notifyUi() },
            onPageFinishedExtra = { webView, _ ->
                if (AppPrefs.autoTranslateEnabled(this)) {
                    translateManager.translatePage(webView, AppPrefs.targetLang(this))
                }
            },
            desktopModeProvider = { AppPrefs.desktopModeEnabled(this) },
            adBlockProvider = { AppPrefs.adBlockEnabled(this) },
            onTabClosed = { webView -> controlServer?.forgetWebView(webView) },
            onChallengeDetected = { url -> notifyChallengeDetected(url) }
        )
        tabManager.newTab(SearchEngines.homepage(this))

        startControlServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        controlServer?.stop()
        super.onDestroy()
    }

    fun setUaPreset(id: String) {
        currentUaPresetId = id
        tabManager.applyUaToAllTabs()
    }

    fun addUiListener(listener: () -> Unit) {
        uiListeners.add(listener)
    }

    fun removeUiListener(listener: () -> Unit) {
        uiListeners.remove(listener)
    }

    private fun notifyUi() {
        uiListeners.forEach { it() }
        updateNotification()
    }

    private fun startControlServer() {
        controlServer = ControlServer(
            port = 8848,
            context = applicationContext,
            authToken = AppPrefs.controlToken(applicationContext),
            setUaPreset = { id -> setUaPreset(id) },
            webViewProvider = { tabManager.activeWebView() },
            tabManager = tabManager,
            userScriptManager = userScriptManager,
            translateManager = translateManager,
            defaultTargetLang = AppPrefs.defaultTargetLang(),
            adBlockEnabledProvider = { AppPrefs.adBlockEnabled(this) },
            setAdBlockEnabled = { enabled -> AppPrefs.edit(this).putBoolean("adblock_enabled", enabled).apply() }
        )
        controlServer?.serviceStartTime = serviceStartTime
        try {
            controlServer?.start()
        } catch (e: Exception) {
            updateNotification("Lỗi khởi động server: ${e.message}")
        }
    }

    private fun updateNotification(extra: String? = null) {
        val tabCount = if (::tabManager.isInitialized) tabManager.tabCount() else 0
        val text = extra ?: "API: 127.0.0.1:8848 • $tabCount tab đang mở"
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // Rate-limited to one push per 10s: onPageFinished can fire more than
    // once while a challenge page is still settling (redirects, iframes),
    // and nobody needs the same "go solve this" alert five times in a row.
    private var lastChallengeNotifyAt = 0L

    private fun notifyChallengeDetected(url: String) {
        val now = System.currentTimeMillis()
        if (now - lastChallengeNotifyAt < 10_000L) return
        lastChallengeNotifyAt = now

        val channelId = ensureChallengeChannel()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Cần giải challenge thủ công")
            .setContentText(if (url.isBlank()) "Một tab đang gặp Cloudflare/CAPTCHA." else url)
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(CHALLENGE_NOTIFICATION_ID, notification)
    }

    private fun ensureChallengeChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHALLENGE_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHALLENGE_CHANNEL_ID,
                    "MoonLite challenge",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Báo khi gặp Cloudflare/CAPTCHA cần giải tay" }
                manager.createNotificationChannel(channel)
            }
        }
        return CHALLENGE_CHANNEL_ID
    }

    private fun buildNotification(text: String): Notification {
        val channelId = ensureChannel()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("MoonLite đang chạy nền")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun ensureChannel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "MoonLite nền",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Giữ trình duyệt và control server chạy khi ở nền" }
                manager.createNotificationChannel(channel)
            }
        }
        return CHANNEL_ID
    }

    companion object {
        private const val CHANNEL_ID = "moonlite_background"
        private const val NOTIFICATION_ID = 1
        private const val CHALLENGE_CHANNEL_ID = "moonlite_challenge"
        private const val CHALLENGE_NOTIFICATION_ID = 2
    }
}
