package com.mono.appdimmer.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: ImageBitmap,
)

object InstalledApps {

    /**
     * Returns every app that has a launcher entry, excluding App Dimmer itself.
     * Icons are rasterised once here so the list scrolls smoothly.
     */
    suspend fun load(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(launcherIntent, 0)

        val seen = HashSet<String>()
        resolved.asSequence()
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != context.packageName }
            .filter { seen.add(it.packageName) }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = info.loadLabel(packageManager).toString(),
                    icon = info.loadIcon(packageManager)
                        .toBitmap(96, 96)
                        .asImageBitmap(),
                )
            }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
            .toList()
    }
}
