package com.deskpilot.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.deskpilot.actions.DeskAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("deskpilot_settings")
enum class MultiFingerAction(val label: String) {
    NONE("No action"), TASK_VIEW("Task View"), SHOW_DESKTOP("Show desktop"),
    SWITCH_APP_PREVIOUS("Previous app"), SWITCH_APP_NEXT("Next app"),
    DESKTOP_LEFT("Previous desktop"), DESKTOP_RIGHT("Next desktop"), SEARCH("Windows Search"),
    VOLUME_UP("Volume up"), VOLUME_DOWN("Volume down"), MUTE("Mute"),
    TRACK_PREVIOUS("Previous track"), TRACK_NEXT("Next track"), PLAY_PAUSE("Play / pause")
}

enum class MultiFingerSlot(val label: String) {
    THREE_UP("3 fingers up"), THREE_DOWN("3 fingers down"), THREE_LEFT("3 fingers left"), THREE_RIGHT("3 fingers right"), THREE_TAP("3-finger tap"),
    FOUR_UP("4 fingers up"), FOUR_DOWN("4 fingers down"), FOUR_LEFT("4 fingers left"), FOUR_RIGHT("4 fingers right"), FOUR_TAP("4-finger tap"),
    FIVE_UP("5 fingers up"), FIVE_DOWN("5 fingers down"), FIVE_LEFT("5 fingers left"), FIVE_RIGHT("5 fingers right"), FIVE_TAP("5-finger tap")
}

data class MultiFingerBindings(
    val threeUp: MultiFingerAction = MultiFingerAction.TASK_VIEW,
    val threeDown: MultiFingerAction = MultiFingerAction.SHOW_DESKTOP,
    val threeLeft: MultiFingerAction = MultiFingerAction.SWITCH_APP_PREVIOUS,
    val threeRight: MultiFingerAction = MultiFingerAction.SWITCH_APP_NEXT,
    val threeTap: MultiFingerAction = MultiFingerAction.SEARCH,
    val fourUp: MultiFingerAction = MultiFingerAction.NONE,
    val fourDown: MultiFingerAction = MultiFingerAction.NONE,
    val fourLeft: MultiFingerAction = MultiFingerAction.DESKTOP_LEFT,
    val fourRight: MultiFingerAction = MultiFingerAction.DESKTOP_RIGHT,
    val fourTap: MultiFingerAction = MultiFingerAction.NONE,
    val fiveUp: MultiFingerAction = MultiFingerAction.NONE,
    val fiveDown: MultiFingerAction = MultiFingerAction.NONE,
    val fiveLeft: MultiFingerAction = MultiFingerAction.NONE,
    val fiveRight: MultiFingerAction = MultiFingerAction.NONE,
    val fiveTap: MultiFingerAction = MultiFingerAction.NONE
) {
    operator fun get(slot: MultiFingerSlot): MultiFingerAction = when (slot) {
        MultiFingerSlot.THREE_UP -> threeUp; MultiFingerSlot.THREE_DOWN -> threeDown; MultiFingerSlot.THREE_LEFT -> threeLeft; MultiFingerSlot.THREE_RIGHT -> threeRight; MultiFingerSlot.THREE_TAP -> threeTap
        MultiFingerSlot.FOUR_UP -> fourUp; MultiFingerSlot.FOUR_DOWN -> fourDown; MultiFingerSlot.FOUR_LEFT -> fourLeft; MultiFingerSlot.FOUR_RIGHT -> fourRight; MultiFingerSlot.FOUR_TAP -> fourTap
        MultiFingerSlot.FIVE_UP -> fiveUp; MultiFingerSlot.FIVE_DOWN -> fiveDown; MultiFingerSlot.FIVE_LEFT -> fiveLeft; MultiFingerSlot.FIVE_RIGHT -> fiveRight; MultiFingerSlot.FIVE_TAP -> fiveTap
    }
}
data class DeskSettings(
    val pointerSpeed: Float = 1.15f, val acceleration: Boolean = true, val accelerationStrength: Float = .32f,
    val precisionMultiplier: Float = .42f, val scrollSpeed: Float = .055f, val naturalScroll: Boolean = true,
    val useVolumeShortcuts: Boolean = false, val volumeUp: DeskAction = DeskAction.COPY, val volumeDown: DeskAction = DeskAction.PASTE,
    val haptics: Boolean = true, val keepAwake: Boolean = true, val leftHanded: Boolean = false, val darkTheme: Boolean = false,
    val extremeDark: Boolean = false, val minimalMode: Boolean = false, val scrollHaptics: Boolean = true,
    val advancedLaptopGestures: Boolean = false, val pinchToZoom: Boolean = true,
    val multiFingerBindings: MultiFingerBindings = MultiFingerBindings(),
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
        val legacyMultiFingerPreset = stringPreferencesKey("multi_finger_preset")
        val multiFingerPrefix = "multi_finger_"
        val developerDiagnostics = booleanPreferencesKey("developer_diagnostics")
    }
    val settings: Flow<DeskSettings> = context.dataStore.data.map { p ->
        DeskSettings(p[Key.speed] ?: 1.15f, p[Key.acceleration] ?: true, p[Key.accelerationStrength] ?: .32f, p[Key.precision] ?: .42f,
            p[Key.scroll] ?: .055f, p[Key.natural] ?: true, p[Key.volume] ?: false,
            action(p[Key.up], DeskAction.COPY), action(p[Key.down], DeskAction.PASTE), p[Key.haptics] ?: true, p[Key.awake] ?: true,
            p[Key.left] ?: false, p[Key.dark] ?: false, p[Key.extremeDark] ?: false, p[Key.minimal] ?: false,
            p[Key.scrollHaptics] ?: true, p[Key.advancedGestures] ?: false, p[Key.pinchZoom] ?: true,
            multiFingerBindings(p),
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
    suspend fun setMultiFingerAction(slot: MultiFingerSlot, value: MultiFingerAction) { context.dataStore.edit { it[multiFingerKey(slot)] = value.name } }
    suspend fun setDeveloperDiagnostics(value: Boolean) { context.dataStore.edit { it[Key.developerDiagnostics] = value } }
    suspend fun setKeepAwake(value: Boolean) { context.dataStore.edit { it[Key.awake] = value } }
    private fun action(name: String?, fallback: DeskAction) = name?.let { runCatching { DeskAction.valueOf(it) }.getOrNull() } ?: fallback
    private fun multiFingerBindings(p: Preferences): MultiFingerBindings {
        // Preserve the last release's one-time preset choice until the user
        // chooses an individual binding in this release.
        val defaults = when (p[Key.legacyMultiFingerPreset]) {
            "MEDIA_CONTROLS" -> MultiFingerBindings(
                threeUp = MultiFingerAction.VOLUME_UP,
                threeDown = MultiFingerAction.VOLUME_DOWN,
                threeLeft = MultiFingerAction.TRACK_PREVIOUS,
                threeRight = MultiFingerAction.TRACK_NEXT,
                threeTap = MultiFingerAction.PLAY_PAUSE
            )
            "OFF" -> MultiFingerBindings(
                threeUp = MultiFingerAction.NONE, threeDown = MultiFingerAction.NONE,
                threeLeft = MultiFingerAction.NONE, threeRight = MultiFingerAction.NONE,
                threeTap = MultiFingerAction.NONE, fourLeft = MultiFingerAction.NONE,
                fourRight = MultiFingerAction.NONE
            )
            else -> MultiFingerBindings()
        }
        return MultiFingerBindings(
            threeUp = multiFingerAction(p[multiFingerKey(MultiFingerSlot.THREE_UP)], defaults[MultiFingerSlot.THREE_UP]),
            threeDown = multiFingerAction(p[multiFingerKey(MultiFingerSlot.THREE_DOWN)], defaults[MultiFingerSlot.THREE_DOWN]),
            threeLeft = multiFingerAction(p[multiFingerKey(MultiFingerSlot.THREE_LEFT)], defaults[MultiFingerSlot.THREE_LEFT]),
            threeRight = multiFingerAction(p[multiFingerKey(MultiFingerSlot.THREE_RIGHT)], defaults[MultiFingerSlot.THREE_RIGHT]),
            threeTap = multiFingerAction(p[multiFingerKey(MultiFingerSlot.THREE_TAP)], defaults[MultiFingerSlot.THREE_TAP]),
            fourUp = multiFingerAction(p[multiFingerKey(MultiFingerSlot.FOUR_UP)], defaults[MultiFingerSlot.FOUR_UP]),
            fourDown = multiFingerAction(p[multiFingerKey(MultiFingerSlot.FOUR_DOWN)], defaults[MultiFingerSlot.FOUR_DOWN]),
            fourLeft = multiFingerAction(p[multiFingerKey(MultiFingerSlot.FOUR_LEFT)], defaults[MultiFingerSlot.FOUR_LEFT]),
            fourRight = multiFingerAction(p[multiFingerKey(MultiFingerSlot.FOUR_RIGHT)], defaults[MultiFingerSlot.FOUR_RIGHT]),
            fourTap = multiFingerAction(p[multiFingerKey(MultiFingerSlot.FOUR_TAP)], defaults[MultiFingerSlot.FOUR_TAP]),
            fiveUp = multiFingerAction(p[multiFingerKey(MultiFingerSlot.FIVE_UP)], defaults[MultiFingerSlot.FIVE_UP]),
            fiveDown = multiFingerAction(p[multiFingerKey(MultiFingerSlot.FIVE_DOWN)], defaults[MultiFingerSlot.FIVE_DOWN]),
            fiveLeft = multiFingerAction(p[multiFingerKey(MultiFingerSlot.FIVE_LEFT)], defaults[MultiFingerSlot.FIVE_LEFT]),
            fiveRight = multiFingerAction(p[multiFingerKey(MultiFingerSlot.FIVE_RIGHT)], defaults[MultiFingerSlot.FIVE_RIGHT]),
            fiveTap = multiFingerAction(p[multiFingerKey(MultiFingerSlot.FIVE_TAP)], defaults[MultiFingerSlot.FIVE_TAP])
        )
    }
    private fun multiFingerKey(slot: MultiFingerSlot) = stringPreferencesKey(Key.multiFingerPrefix + slot.name.lowercase())
    private fun multiFingerAction(name: String?, fallback: MultiFingerAction) = name?.let { runCatching { MultiFingerAction.valueOf(it) }.getOrNull() } ?: fallback
}
