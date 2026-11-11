package com.mono.appdimmer

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import com.mono.appdimmer.ui.AppDimmerScreen
import com.mono.appdimmer.ui.theme.AppDimmerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

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
                    onToggleEnabled = viewModel::onToggleEnabled,
                    onEngineChange = viewModel::onEngineChange,
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
}
