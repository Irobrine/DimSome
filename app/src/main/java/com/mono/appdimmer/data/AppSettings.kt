package com.mono.appdimmer.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Small SharedPreferences-backed store shared by the UI, the overlay service and
 * the boot receiver. The service listens for changes so slider moves and app
 * selections are reflected immediately.
 */
enum class OverlayEngine(val storageValue: String) {
    ACCESSIBILITY("accessibility"),
    COMPATIBILITY("compatibility");

    companion object {
        fun fromStorage(value: String?): OverlayEngine =
            entries.firstOrNull { it.storageValue == value } ?: ACCESSIBILITY
    }
}

object AppSettings {

    private const val PREFS_NAME = "app_dimmer_settings"

    const val KEY_ENABLED = "enabled"
    const val KEY_OPACITY = "opacity"
    const val KEY_SELECTED_APPS = "selected_apps"
    const val KEY_ENGINE = "engine"

    const val DEFAULT_OPACITY = 60

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun opacity(context: Context): Int =
        prefs(context).getInt(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(0, 100)

    fun setOpacity(context: Context, opacity: Int) {
        prefs(context).edit().putInt(KEY_OPACITY, opacity.coerceIn(0, 100)).apply()
    }

    fun selectedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SELECTED_APPS, null)?.toSet().orEmpty()

    fun setSelectedApps(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SELECTED_APPS, packages.toSet()).apply()
    }

    fun engine(context: Context): OverlayEngine =
        OverlayEngine.fromStorage(prefs(context).getString(KEY_ENGINE, null))

    fun setEngine(context: Context, engine: OverlayEngine) {
        prefs(context).edit().putString(KEY_ENGINE, engine.storageValue).apply()
    }

    fun addListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener,
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}
