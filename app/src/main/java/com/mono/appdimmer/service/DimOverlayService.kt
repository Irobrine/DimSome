package com.mono.appdimmer.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mono.appdimmer.MainActivity
import com.mono.appdimmer.R
import com.mono.appdimmer.data.AppSettings
import com.mono.appdimmer.data.OverlayEngine
import com.mono.appdimmer.data.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compatibility engine: a foreground service owns a
 * [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY] window and detects the
 * foreground app through Usage Access instead of an AccessibilityService.
 *
 * This path is independent of the accessibility service, so an OEM killing or
 * unbinding the accessibility service no longer disables dimming. Android caps
 * non-touchable application overlays at 80% opacity, which is reflected in the
 * UI.
 */
class DimOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var windowManager: WindowManager
    private var keyguardManager: KeyguardManager? = null
    private var usageStatsManager: UsageStatsManager? = null

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAlpha = -1f
    private var foregroundPackage: String? = null
    private var pendingForegroundPackage: String? = null
    private var lastUsagePackage: String? = null
    private var screenOn = true
    private var unlocked = true
    private var initialized = false
    private var foregroundStarted = false

    private val hideRunnable = Runnable {
        val packageName = pendingForegroundPackage ?: return@Runnable
        pendingForegroundPackage = null
        if (packageName == foregroundPackage) return@Runnable
        foregroundPackage = packageName
        refreshOverlay()
    }

    private val rotationRunnable = Runnable {
        if (!initialized || !shouldDimNow()) return@Runnable
        val view = overlayView ?: return@Runnable
        if (!view.isAttachedToWindow) return@Runnable
        val params = overlayParams ?: createOverlayParams()
        params.alpha = effectiveAlpha()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            AppSettings.KEY_ENABLED -> {
                if (!AppSettings.isEnabled(this)) {
                    stopSelf()
                } else {
                    startPolling()
                    refreshOverlay()
                }
            }

            AppSettings.KEY_ENGINE -> {
                if (AppSettings.engine(this) != OverlayEngine.COMPATIBILITY) {
                    stopSelf()
                } else {
                    startPolling()
                    refreshOverlay()
                }
            }

            AppSettings.KEY_OPACITY,
            AppSettings.KEY_SELECTED_APPS,
            -> refreshOverlay()
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    refreshOverlay()
                }

                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    unlocked = keyguardManager?.isKeyguardLocked != true
                    refreshOverlay()
                }

                Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    unlocked = true
                    refreshOverlay()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        keyguardManager = getSystemService(KeyguardManager::class.java)
        usageStatsManager = getSystemService(UsageStatsManager::class.java)
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        unlocked = keyguardManager?.isKeyguardLocked != true

        createNotificationChannel()

        if (!canRun()) {
            stopSelf()
            return
        }

        startAsForeground()
        initialized = true

        AppSettings.addListener(this, prefsListener)
        registerScreenReceiver()
        startPolling()
        refreshOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppSettings.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!canRun()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startAsForeground()
        if (initialized) {
            startPolling()
            refreshOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.removeCallbacks(rotationRunnable)
        handler.postDelayed(rotationRunnable, ROTATION_SETTLE_MS)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        AppSettings.removeListener(this, prefsListener)
        runCatching { unregisterReceiver(screenReceiver) }
        removeOverlay()
        initialized = false
        super.onDestroy()
    }

    private fun canRun(): Boolean =
        AppSettings.isEnabled(this) &&
            AppSettings.engine(this) == OverlayEngine.COMPATIBILITY &&
            PermissionUtils.canDrawOverlays(this) &&
            PermissionUtils.hasUsageAccess(this)

    private fun startAsForeground() {
        if (foregroundStarted) return
        foregroundStarted = true

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DimOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dim)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
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

    private fun startPolling() {
        if (!initialized || pollJob?.isActive == true) return
        pollJob = serviceScope.launch {
            var firstQuery = true
            while (isActive) {
                if (!screenOn) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }

                if (!PermissionUtils.hasUsageAccess(this@DimOverlayService) ||
                    !PermissionUtils.canDrawOverlays(this@DimOverlayService)
                ) {
                    stopSelf()
                    return@launch
                }

                val currentlyUnlocked = keyguardManager?.isKeyguardLocked != true
                if (currentlyUnlocked != unlocked) {
                    unlocked = currentlyUnlocked
                    refreshOverlay()
                }

                if (unlocked) {
                    val packageName = withContext(Dispatchers.IO) {
                        queryForegroundPackage(firstQuery)
                    }
                    firstQuery = false
                    if (!packageName.isNullOrBlank()) {
                        updateForeground(packageName)
                    }
                    refreshOverlay()
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Returns the most recently resumed activity package. The first query looks
     * back a day so the service can recover the current app immediately after a
     * restart; later queries only inspect the last few seconds.
     */
    private fun queryForegroundPackage(firstQuery: Boolean): String? {
        val manager = usageStatsManager ?: return lastUsagePackage
        val now = System.currentTimeMillis()
        val begin = now - if (firstQuery) INITIAL_QUERY_MS else POLL_QUERY_MS

        val events = runCatching { manager.queryEvents(begin, now) }.getOrNull()
            ?: return lastUsagePackage
        val event = UsageEvents.Event()
        var latest = lastUsagePackage

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                latest = event.packageName
            }
        }

        lastUsagePackage = latest
        return latest
    }

    private fun updateForeground(packageName: String) {
        if (packageName == foregroundPackage) {
            pendingForegroundPackage = null
            handler.removeCallbacks(hideRunnable)
            refreshOverlay()
            return
        }

        if (packageName == this.packageName ||
            AppSettings.selectedApps(this).contains(packageName)
        ) {
            pendingForegroundPackage = null
            handler.removeCallbacks(hideRunnable)
            foregroundPackage = packageName
            refreshOverlay()
            return
        }

        // Do not hide instantly on a transient window; wait for the next poll
        // or two so rotations/system transitions do not flicker the overlay.
        if (!shouldDimNow()) {
            pendingForegroundPackage = null
            handler.removeCallbacks(hideRunnable)
            foregroundPackage = packageName
            refreshOverlay()
            return
        }

        if (pendingForegroundPackage == packageName) return
        pendingForegroundPackage = packageName
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, FOREGROUND_GRACE_MS)
    }

    private fun shouldDimNow(): Boolean {
        if (!AppSettings.isEnabled(this)) return false
        if (AppSettings.engine(this) != OverlayEngine.COMPATIBILITY) return false
        if (!screenOn || !unlocked) return false
        if (!PermissionUtils.canDrawOverlays(this)) return false
        if (!PermissionUtils.hasUsageAccess(this)) return false
        if (AppSettings.opacity(this) <= 0) return false

        val packageName = foregroundPackage ?: return false
        if (packageName == this.packageName) return false

        return AppSettings.selectedApps(this).contains(packageName)
    }

    private fun effectiveAlpha(): Float =
        (AppSettings.opacity(this) / 100f).coerceIn(0f, MAX_APPLICATION_OVERLAY_ALPHA)

    private fun refreshOverlay() {
        if (!shouldDimNow()) {
            removeOverlay()
            return
        }
        showOverlay()
    }

    private fun showOverlay() {
        val alpha = effectiveAlpha()
        var view = overlayView

        if (view != null && !view.isAttachedToWindow) {
            overlayView = null
            overlayParams = null
            overlayAlpha = -1f
            runCatching { windowManager.removeViewImmediate(view) }
            view = null
        }

        if (view == null) {
            val newView = View(this).apply {
                setBackgroundColor(Color.BLACK)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isClickable = false
                isFocusable = false
            }
            val params = createOverlayParams().apply {
                this.alpha = alpha
            }
            runCatching {
                windowManager.addView(newView, params)
                overlayView = newView
                overlayParams = params
                overlayAlpha = alpha
            }
            return
        }

        if (overlayAlpha != alpha) {
            val params = overlayParams ?: createOverlayParams().apply {
                this.alpha = alpha
            }
            params.alpha = alpha
            runCatching { windowManager.updateViewLayout(view, params) }
            overlayParams = params
            overlayAlpha = alpha
        }
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        overlayView = null
        overlayParams = null
        overlayAlpha = -1f
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun createOverlayParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

    companion object {
        const val ACTION_STOP = "com.mono.appdimmer.action.STOP_COMPAT"

        private const val CHANNEL_ID = "compat_overlay"
        private const val NOTIFICATION_ID = 1002

        /** Android 12+ caps non-touchable application overlays at 80%. */
        private const val MAX_APPLICATION_OVERLAY_ALPHA = 0.8f

        private const val POLL_INTERVAL_MS = 500L
        private const val POLL_QUERY_MS = 3_000L
        private const val INITIAL_QUERY_MS = 24 * 60 * 60 * 1000L
        private const val FOREGROUND_GRACE_MS = 600L
        private const val ROTATION_SETTLE_MS = 300L
    }
}
