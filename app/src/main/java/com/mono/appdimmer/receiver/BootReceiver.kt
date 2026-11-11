package com.mono.appdimmer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mono.appdimmer.data.AppSettings
import com.mono.appdimmer.data.OverlayEngine
import com.mono.appdimmer.data.PermissionUtils
import com.mono.appdimmer.service.DimOverlayService

/**
 * Restores the compatibility overlay after a reboot or app update. The
 * accessibility engine is system-managed and does not need this.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!AppSettings.isEnabled(context)) return
        if (AppSettings.engine(context) != OverlayEngine.COMPATIBILITY) return
        if (!PermissionUtils.canDrawOverlays(context)) return
        if (!PermissionUtils.hasUsageAccess(context)) return

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DimOverlayService::class.java),
            )
        }
    }
}
