package com.mono.appdimmer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.mono.appdimmer.data.AppSettings
import com.mono.appdimmer.data.OverlayEngine
import com.mono.appdimmer.ui.AppDimmerScreen
import com.mono.appdimmer.ui.theme.AppDimmerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The compatibility service still runs if notifications are denied. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        setContent {
            AppDimmerTheme {
                val state by viewModel.uiState

                AppDimmerScreen(
                    state = state,
                    onToggleEnabled = { enabled ->
                        viewModel.onToggleEnabled(enabled)
                        if (enabled && AppSettings.engine(this) == OverlayEngine.COMPATIBILITY) {
                            maybeRequestNotificationPermission()
                        }
                    },
                    onEngineChange = { engine ->
                        viewModel.onEngineChange(engine)
                        if (engine == OverlayEngine.COMPATIBILITY &&
                            viewModel.uiState.value.enabled
                        ) {
                            maybeRequestNotificationPermission()
                        }
                    },
                    onOpacityChange = viewModel::onOpacityChange,
                    onToggleApp = viewModel::onToggleApp,
                    onClearSelection = viewModel::onClearSelection,
                    onRequestOverlayPermission = ::openOverlaySettings,
                    onRequestUsageAccess = ::openUsageAccessSettings,
                    onRequestAccessibility = ::openAccessibilitySettings,
                    onDismissAccessibilityDialog = viewModel::dismissAccessibilityDialog,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshOnResume()
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            }
    }

    private fun openUsageAccessSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    private fun openAccessibilitySettings() {
        viewModel.dismissAccessibilityDialog()
        runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
