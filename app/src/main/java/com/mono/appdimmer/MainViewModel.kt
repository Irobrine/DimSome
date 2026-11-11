package com.mono.appdimmer

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mono.appdimmer.data.AppSettings
import com.mono.appdimmer.data.InstalledApp
import com.mono.appdimmer.data.InstalledApps
import com.mono.appdimmer.data.OverlayEngine
import com.mono.appdimmer.data.PermissionUtils
import com.mono.appdimmer.service.DimOverlayService
import kotlinx.coroutines.launch

data class MainUiState(
    val enabled: Boolean = false,
    val opacity: Int = AppSettings.DEFAULT_OPACITY,
    val selectedPackages: Set<String> = emptySet(),
    val apps: List<InstalledApp> = emptyList(),
    val appsLoading: Boolean = true,
    val engine: OverlayEngine = OverlayEngine.ACCESSIBILITY,
    val overlayGranted: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val showAccessibilityDialog: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val _uiState = mutableStateOf(MainUiState())
    val uiState: State<MainUiState> = _uiState

    init {
        _uiState.value = _uiState.value.copy(
            enabled = AppSettings.isEnabled(app),
            opacity = AppSettings.opacity(app),
            selectedPackages = AppSettings.selectedApps(app),
            engine = AppSettings.engine(app),
            overlayGranted = PermissionUtils.canDrawOverlays(app),
            usageAccessGranted = PermissionUtils.hasUsageAccess(app),
            accessibilityEnabled = PermissionUtils.isAccessibilityEnabled(app),
        )

        loadApps()
        ensureServices()
    }

    private fun loadApps() {
        viewModelScope.launch {
            // Labels first so the list and alphabet sidebar appear immediately.
            val metadata = runCatching { InstalledApps.loadMetadata(app) }
                .getOrDefault(emptyList())
            val installedPackages = metadata.mapTo(HashSet()) { it.packageName }
            val cleanedSelection = _uiState.value.selectedPackages.intersect(installedPackages)

            if (cleanedSelection != _uiState.value.selectedPackages) {
                AppSettings.setSelectedApps(app, cleanedSelection)
            }

            _uiState.value = _uiState.value.copy(
                apps = metadata,
                appsLoading = false,
                selectedPackages = cleanedSelection,
            )

            // Icons are expensive; fill them in without blocking the list.
            val withIcons = runCatching { InstalledApps.loadIcons(app, metadata) }
                .getOrDefault(metadata)
            _uiState.value = _uiState.value.copy(apps = withIcons)
        }
    }

    /** Re-reads permissions and preferences whenever the screen resumes. */
    fun refreshOnResume() {
        val engine = AppSettings.engine(app)
        val accessibilityEnabled = PermissionUtils.isAccessibilityEnabled(app)
        val overlayGranted = PermissionUtils.canDrawOverlays(app)
        val usageAccessGranted = PermissionUtils.hasUsageAccess(app)

        _uiState.value = _uiState.value.copy(
            enabled = AppSettings.isEnabled(app),
            opacity = AppSettings.opacity(app),
            selectedPackages = AppSettings.selectedApps(app),
            engine = engine,
            overlayGranted = overlayGranted,
            usageAccessGranted = usageAccessGranted,
            accessibilityEnabled = accessibilityEnabled,
            showAccessibilityDialog = if (engine == OverlayEngine.ACCESSIBILITY && !accessibilityEnabled) {
                _uiState.value.showAccessibilityDialog
            } else {
                false
            },
        )

        ensureServices()
    }

    fun onToggleEnabled(wantEnabled: Boolean) {
        val engine = AppSettings.engine(app)
        AppSettings.setEnabled(app, wantEnabled)
        _uiState.value = _uiState.value.copy(
            enabled = wantEnabled,
            showAccessibilityDialog = wantEnabled &&
                engine == OverlayEngine.ACCESSIBILITY &&
                !PermissionUtils.isAccessibilityEnabled(app),
        )
        ensureServices()
    }

    fun onEngineChange(engine: OverlayEngine) {
        if (engine == _uiState.value.engine) return

        AppSettings.setEngine(app, engine)
        _uiState.value = _uiState.value.copy(
            engine = engine,
            showAccessibilityDialog = engine == OverlayEngine.ACCESSIBILITY &&
                AppSettings.isEnabled(app) &&
                !PermissionUtils.isAccessibilityEnabled(app),
        )
        ensureServices()
    }

    fun onOpacityChange(value: Int) {
        val opacity = value.coerceIn(0, 100)
        if (opacity == _uiState.value.opacity) return

        _uiState.value = _uiState.value.copy(opacity = opacity)
        AppSettings.setOpacity(app, opacity)
    }

    fun onToggleApp(packageName: String) {
        val current = _uiState.value.selectedPackages
        val updated = if (packageName in current) {
            current - packageName
        } else {
            current + packageName
        }

        _uiState.value = _uiState.value.copy(selectedPackages = updated)
        AppSettings.setSelectedApps(app, updated)
    }

    fun onClearSelection() {
        _uiState.value = _uiState.value.copy(selectedPackages = emptySet())
        AppSettings.setSelectedApps(app, emptySet())
    }

    fun dismissAccessibilityDialog() {
        _uiState.value = _uiState.value.copy(showAccessibilityDialog = false)
    }

    /**
     * The compatibility engine needs its foreground service. The accessibility
     * engine is system-managed and only needs the accessibility service to be
     * enabled, so the compatibility service is stopped in that mode.
     */
    private fun ensureServices() {
        val enabled = AppSettings.isEnabled(app)
        val engine = AppSettings.engine(app)
        val canRunCompatibility = enabled &&
            engine == OverlayEngine.COMPATIBILITY &&
            PermissionUtils.canDrawOverlays(app) &&
            PermissionUtils.hasUsageAccess(app)

        if (canRunCompatibility) {
            runCatching {
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, DimOverlayService::class.java),
                )
            }
        } else {
            app.stopService(Intent(app, DimOverlayService::class.java))
        }
    }
}
