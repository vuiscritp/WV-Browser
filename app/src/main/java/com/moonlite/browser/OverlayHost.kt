package com.moonlite.browser

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout

/**
 * The real fix for "a background tab doesn't render/update unless I'm
 * actually looking at it" — a tab whose WebView is fully detached (parent
 * == null, not part of any Window at all) is exactly what Chromium treats
 * as "not visible", which is what makes many sites' own lazy-render logic
 * (Page Visibility API, IntersectionObserver) skip work entirely. The
 * earlier fix for this (see TabManager's FORCE_VISIBLE_JS) only spoofs
 * what JS *reads back* — this instead keeps every tab's WebView genuinely
 * attached to a real, live Window at all times, which is what the engine
 * itself actually keys off of.
 *
 * How: a 1-pixel-alpha, untouchable, unfocusable overlay window owned by
 * the *service* (not the Activity) — so it outlives MainActivity closing
 * entirely. Every tab not currently shown in MainActivity's own container
 * gets parked here instead of being detached to nothing. Requires the
 * "Display over other apps" special permission (`SYSTEM_ALERT_WINDOW`) —
 * without it, this silently does nothing and tabs fall back to the old
 * (JS-spoof-only) behavior; it never crashes or blocks core functionality
 * over a missing permission.
 *
 * Honest limits: this makes Android/Chromium consider the tab *visible and
 * attached*, which is the actual signal most real-world "won't render in
 * background" cases key off. It does not, and cannot from any public API,
 * override Chromium's own internal frame-rate throttling decisions for a
 * window it still considers occluded/backgrounded at the OS level — some
 * OEM battery-optimization layers may still throttle a fully alpha-0
 * window more aggressively than a genuinely visible one. There's no way to
 * verify that across every OEM's WebView build without real-device testing
 * per vendor.
 */
class OverlayHost(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var container: FrameLayout? = null

    fun isPermissionGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** Idempotent — safe to call repeatedly (e.g. every time a tab is parked). */
    private fun ensureAttached(): FrameLayout? {
        container?.let { return it }
        if (!isPermissionGranted()) return null

        val params = WindowManager.LayoutParams(
            OVERLAY_WIDTH_PX,
            OVERLAY_HEIGHT_PX,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 0f // real, composited, attached window — just invisible
        }

        val frame = FrameLayout(context)
        return try {
            windowManager.addView(frame, params)
            container = frame
            frame
        } catch (e: Exception) {
            // Permission revoked mid-flight, or an OEM restricts overlay
            // windows from a Service context specifically — fail closed,
            // callers fall back to the pre-existing detached behavior.
            null
        }
    }

    /** Moves [webView] here, out of wherever it currently is. No-op (leaves it wherever it was) if the permission isn't granted. */
    fun parkTab(webView: WebView) {
        val host = ensureAttached() ?: return
        if (webView.parent === host) return
        (webView.parent as? ViewGroup)?.removeView(webView)
        host.addView(
            webView,
            ViewGroup.LayoutParams(OVERLAY_WIDTH_PX, OVERLAY_HEIGHT_PX)
        )
    }

    /** Called once, from MoonliteService.onDestroy(), so the overlay window doesn't leak past the service's own lifetime. */
    fun teardown() {
        val c = container ?: return
        try {
            windowManager.removeView(c)
        } catch (e: Exception) {
            // Already removed (e.g. permission revoked and the system tore
            // it down on its own) — nothing left to clean up.
        }
        container = null
    }

    companion object {
        // Matches the app's own default mobile viewport (see
        // ControlServer's defaultScreenshotWidth/Height and UaPresets'
        // MOBILE_SCREEN_WIDTH/HEIGHT) so a parked tab lays out at the same
        // size it would if it were the one actually shown — not some
        // arbitrary different size that would itself be a fingerprinting
        // inconsistency if a script checked window dimensions mid-park.
        private const val OVERLAY_WIDTH_PX = 460
        private const val OVERLAY_HEIGHT_PX = 980
    }
}
