package com.deskpilot.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.deskpilot.actions.DeskAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("deskpilot_settings")
enum class MultiFingerPreset(val label: String) {
    WINDOWS_NAVIGATION("Windows navigation"),
    MEDIA_CONTROLS("Media controls"),
    OFF("Off")
}
data class DeskSettings(
    val pointerSpeed: Float = 1.15f, val acceleration: Boolean = true, val accelerationStrength: Float = .32f,
    val precisionMultiplier: Float = .42f, val scrollSpeed: Float = .055f, val naturalScroll: Boolean = true,
    val useVolumeShortcuts: Boolean = false, val volumeUp: DeskAction = DeskAction.COPY, val volumeDown: DeskAction = DeskAction.PASTE,
    val haptics: Boolean = true, val keepAwake: Boolean = true, val leftHanded: Boolean = false, val darkTheme: Boolean = false,
    val extremeDark: Boolean = false, val minimalMode: Boolean = false, val scrollHaptics: Boolean = true,
    val advancedLaptopGestures: Boolean = false, val pinchToZoom: Boolean = true,
    val multiFingerPreset: MultiFingerPreset = MultiFingerPreset.WINDOWS_NAVIGATION,
    val developerDiagnostics: Boolean = false
)
class SettingsRepository(private val context: Context) {
    private object Key {
        val speed = floatPreferencesKey("pointer_speed"); val acceleration = booleanPreferencesKey("acceleration")
        val accelerationStrength = floatPreferencesKey("acceleration_strength"); val precision = floatPreferencesKey("precision_multiplier")
        val scroll = floatPreferencesKey("scroll_speed"); val natural = booleanPreferencesKey("natural_scroll")
        val volume = booleanPreferencesKey("volume_shortcuts"); val up = stringPreferencesKey("volume_up"); val down = stringPreferencesKey("volume_down")
        val haptics = booleanPreferencesKey("haptics"); val awake = booleanPreferencesKey("awake"); val left = booleanPreferencesKey("left_handed")
        val dark = booleanPreferencesKey("dark_theme"); val extremeDark = booleanPreferencesKey("extreme_dark")
        val minimal = booleanPreferencesKey("minimal_mode")
        val scrollHaptics = booleanPreferencesKey("scroll_haptics")
        val advancedGestures = booleanPreferencesKey("advanced_laptop_gestures")
        val pinchZoom = booleanPreferencesKey("pinch_to_zoom")
        val multiFingerPreset = stringPreferencesKey("multi_finger_preset")
        val developerDiagnostics = booleanPreferencesKey("developer_diagnostics")
    }
    val settings: Flow<DeskSettings> = context.dataStore.data.map { p ->
        DeskSettings(p[Key.speed] ?: 1.15f, p[Key.acceleration] ?: true, p[Key.accelerationStrength] ?: .32f, p[Key.precision] ?: .42f,
            p[Key.scroll] ?: .055f, p[Key.natural] ?: true, p[Key.volume] ?: false,
            action(p[Key.up], DeskAction.COPY), action(p[Key.down], DeskAction.PASTE), p[Key.haptics] ?: true, p[Key.awake] ?: true,
            p[Key.left] ?: false, p[Key.dark] ?: false, p[Key.extremeDark] ?: false, p[Key.minimal] ?: false,
            p[Key.scrollHaptics] ?: true, p[Key.advancedGestures] ?: false, p[Key.pinchZoom] ?: true,
            multiFingerPreset(p[Key.multiFingerPreset]),
            p[Key.developerDiagnostics] ?: false)
    }
    suspend fun setVolumeEnabled(value: Boolean) { context.dataStore.edit { it[Key.volume] = value } }
    suspend fun setPointerSpeed(value: Float) { context.dataStore.edit { it[Key.speed] = value } }
    suspend fun setScrollSpeed(value: Float) { context.dataStore.edit { it[Key.scroll] = value } }
    suspend fun setNaturalScroll(value: Boolean) { context.dataStore.edit { it[Key.natural] = value } }
    suspend fun setVolumeAction(up: Boolean, action: DeskAction) { context.dataStore.edit { it[if (up) Key.up else Key.down] = action.name } }
    suspend fun setDark(value: Boolean) { context.dataStore.edit { it[Key.dark] = value } }
    suspend fun setExtremeDark(value: Boolean) { context.dataStore.edit { it[Key.extremeDark] = value } }
    suspend fun setHaptics(value: Boolean) { context.dataStore.edit { it[Key.haptics] = value } }
    suspend fun setScrollHaptics(value: Boolean) { context.dataStore.edit { it[Key.scrollHaptics] = value } }
    suspend fun setAdvancedLaptopGestures(value: Boolean) { context.dataStore.edit { it[Key.advancedGestures] = value } }
    suspend fun setPinchToZoom(value: Boolean) { context.dataStore.edit { it[Key.pinchZoom] = value } }
    suspend fun setMultiFingerPreset(value: MultiFingerPreset) { context.dataStore.edit { it[Key.multiFingerPreset] = value.name } }
    suspend fun setDeveloperDiagnostics(value: Boolean) { context.dataStore.edit { it[Key.developerDiagnostics] = value } }
    suspend fun setKeepAwake(value: Boolean) { context.dataStore.edit { it[Key.awake] = value } }
    private fun action(name: String?, fallback: DeskAction) = name?.let { runCatching { DeskAction.valueOf(it) }.getOrNull() } ?: fallback
    private fun multiFingerPreset(name: String?) = name?.let { runCatching { MultiFingerPreset.valueOf(it) }.getOrNull() }
        ?: MultiFingerPreset.WINDOWS_NAVIGATION
}
