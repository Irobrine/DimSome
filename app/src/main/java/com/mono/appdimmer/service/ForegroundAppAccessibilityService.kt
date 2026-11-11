package com.mono.appdimmer.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.mono.appdimmer.data.AppSettings
import com.mono.appdimmer.data.OverlayEngine

/**
 * Detects the foreground app and owns the dim overlay.
 *
 * The overlay uses [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
 * because normal application overlays are capped at 80% opacity on Android 12+
 * when they ignore touches. Accessibility overlays are trusted, so they can be
 * fully opaque while still letting every touch pass through.
 *
 * The service only ever reads package names and window types; it never
 * inspects screen content or performs actions.
 */
class ForegroundAppAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private var keyguardManager: KeyguardManager? = null

    private var overlayView: View? = null
    private var overlayTargetAlpha = -1f
    private var foregroundPackage: String? = null
    private var pendingForegroundPackage: String? = null
    private var screenOn = true
    private var unlocked = true
    private var initialized = false

    private val handler = Handler(Looper.getMainLooper())

    private val lockRefreshRunnable = object : Runnable {
        override fun run() {
            if (!screenOn) return

            if (keyguardManager?.isKeyguardLocked == true) {
                // Some devices never deliver ACTION_USER_PRESENT. Keep checking
                // while the lock screen is up; this is a cheap boolean read.
                refreshForeground()
                handler.postDelayed(this, 500L)
                return
            }

            if (!unlocked) {
                unlocked = true
                refreshForeground()
                refreshOverlay(animate = true)
            }
        }
    }

    /**
     * Rotation briefly makes other windows active (the launcher, a system
     * window, or no window at all). Only commit a switch to an unselected app
     * after it has been stable for a moment, so landscape/portrait changes
     * cannot leave the overlay stuck off.
     */
    private val foregroundCommitRunnable = Runnable {
        val packageName = pendingForegroundPackage ?: return@Runnable
        pendingForegroundPackage = null
        if (packageName == foregroundPackage) return@Runnable

        foregroundPackage = packageName
        refreshOverlay(animate = true)
    }

    /** Re-apply the overlay once the new orientation has settled. */
    private val rotationRunnable = Runnable {
        if (!initialized) return@Runnable

        refreshForegroundNow()
        val view = overlayView
        if (view != null && view.isAttachedToWindow && shouldDimNow()) {
            // Re-apply full-screen bounds and the correct alpha without
            // replacing the window (replacing it can stack duplicate overlays
            // if the old window is still being torn down).
            val targetAlpha = AppSettings.opacity(this) / 100f
            view.animate().cancel()
            view.alpha = targetAlpha
            overlayTargetAlpha = targetAlpha
            runCatching { windowManager.updateViewLayout(view, createOverlayParams()) }
        } else {
            refreshOverlay(animate = false)
        }
    }

    /** Fallback for missed accessibility events (some OEMs are unreliable). */
    private val foregroundWatchdog = object : Runnable {
        override fun run() {
            if (!initialized || !screenOn) return
            refreshForeground()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            AppSettings.KEY_ENABLED -> {
                if (AppSettings.isEnabled(this)) {
                    refreshForegroundNow()
                    startWatchdog()
                    refreshOverlay(animate = true)
                } else {
                    stopWatchdog()
                    refreshOverlay(animate = true)
                }
            }

            AppSettings.KEY_SELECTED_APPS -> {
                refreshForegroundNow()
                refreshOverlay(animate = true)
            }

            AppSettings.KEY_OPACITY -> refreshOverlay(animate = true)

            AppSettings.KEY_ENGINE -> {
                if (AppSettings.engine(this) == OverlayEngine.ACCESSIBILITY) {
                    startWatchdog()
                } else {
                    stopWatchdog()
                }
                refreshOverlay(animate = true)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    unlocked = false
                    handler.removeCallbacks(lockRefreshRunnable)
                    stopWatchdog()
                    refreshOverlay(animate = false)
                }

                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    unlocked = keyguardManager?.isKeyguardLocked != true
                    refreshForegroundNow()
                    refreshOverlay(animate = false)
                    startWatchdog()
                    if (!unlocked) scheduleLockRefresh()
                }

                Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    unlocked = true
                    handler.removeCallbacks(lockRefreshRunnable)
                    refreshForegroundNow()
                    refreshOverlay(animate = true)
                    startWatchdog()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        if (initialized) {
            refreshForegroundNow()
            refreshOverlay(animate = false)
            startWatchdog()
            return
        }

        initialized = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        keyguardManager = getSystemService(KeyguardManager::class.java)
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        unlocked = keyguardManager?.isKeyguardLocked != true

        AppSettings.addListener(this, prefsListener)
        registerScreenReceiver()

        refreshForegroundNow()
        refreshOverlay(animate = false)
        startWatchdog()

        if (!unlocked) scheduleLockRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: return
                val window = runCatching {
                    windows.firstOrNull { it.id == event.windowId }
                }.getOrNull()

                if (window == null) {
                    // Window info is not always ready when the event arrives.
                    // Fall back to package filtering so keyboards and the
                    // notification shade never become the "foreground app".
                    if (packageName == SYSTEM_UI_PACKAGE || isInputMethod(packageName)) return
                } else if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    return
                }

                updateForeground(packageName)
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshForeground()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.removeCallbacks(rotationRunnable)
        handler.postDelayed(rotationRunnable, ROTATION_SETTLE_MS)
    }

    private fun resolveForegroundPackage(): String? {
        val applicationWindows = runCatching {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        }.getOrNull().orEmpty()
        if (applicationWindows.isEmpty()) return null

        val window = applicationWindows
            .filter { it.isActive }
            .maxByOrNull { it.layer }
            ?: applicationWindows.maxByOrNull { it.layer }
            ?: return null

        val root = runCatching { window.root }.getOrNull() ?: return null
        val packageName = root.packageName?.toString()
        root.recycle()

        return packageName?.takeIf { it.isNotBlank() }
    }

    private fun refreshForeground() {
        resolveForegroundPackage()?.let(::updateForeground)
    }

    /**
     * Commits the active app immediately (used after rotation / screen changes,
     * where waiting for the grace period would be counter-productive).
     */
    private fun refreshForegroundNow() {
        val packageName = resolveForegroundPackage() ?: return
        pendingForegroundPackage = null
        handler.removeCallbacks(foregroundCommitRunnable)
        foregroundPackage = packageName
        refreshOverlay(animate = false)
    }

    private fun updateForeground(packageName: String) {
        // Our own UI should always be undimmed immediately.
        if (packageName == this.packageName) {
            pendingForegroundPackage = null
            handler.removeCallbacks(foregroundCommitRunnable)
            if (foregroundPackage != packageName) {
                foregroundPackage = packageName
                refreshOverlay(animate = true)
            } else {
                refreshOverlay(animate = false)
            }
            return
        }

        val isTarget = AppSettings.selectedApps(this).contains(packageName)

        if (isTarget) {
            pendingForegroundPackage = null
            handler.removeCallbacks(foregroundCommitRunnable)
            if (foregroundPackage != packageName) {
                foregroundPackage = packageName
                refreshOverlay(animate = true)
            } else {
                // Same app, but a rotation may have re-created its window.
                refreshOverlay(animate = false)
            }
            return
        }

        // The same unselected app event also refreshes a possibly-detached view.
        if (packageName == foregroundPackage) {
            pendingForegroundPackage = null
            handler.removeCallbacks(foregroundCommitRunnable)
            refreshOverlay(animate = false)
            return
        }

        // Do not hide instantly: wait until the unselected app is stable. This
        // is what prevents portrait/landscape transitions from turning the
        // overlay off when the launcher briefly reports itself as focused.
        if (!shouldDimNow()) {
            pendingForegroundPackage = null
            handler.removeCallbacks(foregroundCommitRunnable)
            foregroundPackage = packageName
            refreshOverlay(animate = true)
            return
        }

        // Keep the original deadline for the same pending app. Otherwise apps
        // that emit window events continuously could postpone the hide forever.
        if (pendingForegroundPackage == packageName) return

        pendingForegroundPackage = packageName
        handler.removeCallbacks(foregroundCommitRunnable)
        handler.postDelayed(foregroundCommitRunnable, FOREGROUND_GRACE_MS)
    }

    private fun scheduleLockRefresh() {
        handler.removeCallbacks(lockRefreshRunnable)
        handler.postDelayed(lockRefreshRunnable, 500L)
    }

    private fun startWatchdog() {
        handler.removeCallbacks(foregroundWatchdog)
        if (initialized &&
            screenOn &&
            AppSettings.isEnabled(this) &&
            AppSettings.engine(this) == OverlayEngine.ACCESSIBILITY
        ) {
            handler.postDelayed(foregroundWatchdog, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(foregroundWatchdog)
    }

    private fun isInputMethod(packageName: String): Boolean {
        val inputMethodManager = getSystemService(InputMethodManager::class.java) ?: return false
        return inputMethodManager.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun shouldDimNow(): Boolean {
        if (!AppSettings.isEnabled(this)) return false
        if (AppSettings.engine(this) != OverlayEngine.ACCESSIBILITY) return false
        if (!screenOn) return false
        if (!unlocked) return false
        if (AppSettings.opacity(this) <= 0) return false

        val packageName = foregroundPackage ?: return false
        if (packageName == this.packageName) return false

        return AppSettings.selectedApps(this).contains(packageName)
    }

    private fun refreshOverlay(animate: Boolean) {
        if (!shouldDimNow()) {
            removeOverlay(animate = animate)
            return
        }
        showOverlay(AppSettings.opacity(this), animate = animate)
    }

    private fun showOverlay(opacity: Int, animate: Boolean) {
        val targetAlpha = opacity / 100f
        var view = overlayView

        if (view != null && !view.isAttachedToWindow) {
            overlayView = null
            overlayTargetAlpha = -1f
            runCatching { windowManager.removeViewImmediate(view) }
            view = null
        }
        if (view == null) {
            view = createOverlayView() ?: return
        }

        // Window events arrive often (especially while a page loads), so do
        // not restart the fade on every refresh: it would never reach 100%.
        if (overlayTargetAlpha == targetAlpha) {
            // Heal a fade that was interrupted by a display/config change and
            // left the view invisible while we still thought it was shown.
            if (!animate && view.alpha != targetAlpha) {
                view.animate().cancel()
                view.alpha = targetAlpha
            }
            return
        }
        overlayTargetAlpha = targetAlpha

        view.animate().cancel()
        if (animate) {
            view.animate()
                .alpha(targetAlpha)
                .setDuration(160L)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            view.alpha = targetAlpha
        }
    }

    private fun createOverlayParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

    private fun createOverlayView(): View? {
        val existing = overlayView
        if (existing != null) {
            if (existing.isAttachedToWindow) return existing
            overlayView = null
            overlayTargetAlpha = -1f
            runCatching { windowManager.removeViewImmediate(existing) }
        }

        val view = View(this).apply {
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
            alpha = 0f
        }

        return try {
            windowManager.addView(view, createOverlayParams())
            overlayView = view
            view
        } catch (_: Exception) {
            null
        }
    }

    private fun removeOverlay(animate: Boolean) {
        val view = overlayView ?: return
        overlayTargetAlpha = -1f
        view.animate().cancel()

        if (!animate) {
            detachOverlay(view)
            return
        }

        view.animate()
            .alpha(0f)
            .setDuration(140L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // A new app may have been selected while the fade-out ran.
                if (overlayView === view && !shouldDimNow()) {
                    detachOverlay(view)
                }
            }
            .start()
    }

    private fun detachOverlay(view: View) {
        if (overlayView !== view) return
        overlayView = null
        overlayTargetAlpha = -1f
        // Immediate removal guarantees the old window is gone before a new one
        // can be added; background removal can leave stacked overlays.
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun cleanup() {
        if (!initialized) return
        initialized = false

        handler.removeCallbacksAndMessages(null)
        AppSettings.removeListener(this, prefsListener)
        runCatching { unregisterReceiver(screenReceiver) }

        overlayView?.let { view ->
            overlayView = null
            overlayTargetAlpha = -1f
            runCatching { windowManager.removeViewImmediate(view) }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** How long an unselected app must stay foreground before hiding. */
        private const val FOREGROUND_GRACE_MS = 600L

        /** Time given to the window manager to finish a rotation. */
        private const val ROTATION_SETTLE_MS = 350L

        /** Fallback re-check interval; only runs while the dimmer is enabled. */
        private const val WATCHDOG_INTERVAL_MS = 1_000L
    }
}
