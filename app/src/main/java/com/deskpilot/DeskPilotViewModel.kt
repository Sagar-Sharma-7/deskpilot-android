package com.deskpilot

import android.app.Application
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deskpilot.actions.*
import com.deskpilot.data.*
import com.deskpilot.hid.*
import com.deskpilot.input.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class DeskPilotViewModel(app: Application) : AndroidViewModel(app), GestureSink {
    enum class HapticCue { CLICK, DOUBLE_CLICK, DRAG_START, SHORTCUT, SCROLL, VOLUME_STEP }
    private val repository = SettingsRepository(app)
    private val hid = (app as DeskPilotApplication).hidDeviceManager
    val connection: StateFlow<ConnectionState> = hid.state
    val hidDiagnostics: StateFlow<HidDiagnostics> = hid.diagnostics
    val settings: StateFlow<DeskSettings> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeskSettings())
    private val pointer = PointerEngine()
    private val scroll = ScrollEngine()
    private val _dragLocked = MutableStateFlow(false)
    val dragLocked: StateFlow<Boolean> = _dragLocked.asStateFlow()
    private val _hapticEvents = MutableSharedFlow<HapticCue>(extraBufferCapacity = 4)
    val hapticEvents = _hapticEvents.asSharedFlow()
    private var lastScrollHapticAt = 0L
    private var pinchAccumulator = 0f
    private var multiFingerVolumeDistance = 0f
    private var activeMultiFingerSlot: MultiFingerSlot? = null
    private val multiFingerVolumeStepDistance = app.resources.displayMetrics.density * 12f
    private val actions = ActionRegistry(hid, ::togglePrecision, ::toggleDragLock)
    val gestures = GestureEngine(this, GestureEngine.Config(density = app.resources.displayMetrics.density))
    val gestureDiagnostics: StateFlow<GestureDiagnostics> = gestures.diagnostics

    init { viewModelScope.launch { settings.collect { s ->
        pointer.updateTuning(PointerTuning(s.pointerSpeed, s.acceleration, s.accelerationStrength, s.precisionMultiplier))
        scroll.updateSpeed(s.scrollSpeed)
        gestures.setDiagnosticsEnabled(s.developerDiagnostics)
        hid.setDiagnosticsEnabled(s.developerDiagnostics)
    } } }
    fun startBluetooth(allowed: Boolean) {
        if (allowed) HidForegroundService.start(getApplication())
        hid.start(allowed)
    }
    fun close() { hid.stop(); HidForegroundService.stop(getApplication()) }
    fun releaseInputs() {
        _dragLocked.value = false
        multiFingerVolumeDistance = 0f
        activeMultiFingerSlot = null
        hid.releaseAll()
        pointer.reset()
        scroll.reset()
    }
    fun execute(action: DeskAction) {
        actions.execute(action)
        if (action != DeskAction.NO_ACTION) haptic(HapticCue.SHORTCUT)
    }
    fun rememberedDevices() = hid.rememberedDevices()
    fun connect(device: android.bluetooth.BluetoothDevice) = hid.connect(device)
    fun reconnect() = hid.reconnect()
    fun setVolumeShortcuts(enabled: Boolean) = viewModelScope.launch { repository.setVolumeEnabled(enabled) }
    fun setVolumeAction(up: Boolean, action: DeskAction) = viewModelScope.launch { repository.setVolumeAction(up, action) }
    fun setPointerSpeed(speed: Float) = viewModelScope.launch { repository.setPointerSpeed(speed) }
    fun setScrollSpeed(speed: Float) = viewModelScope.launch { repository.setScrollSpeed(speed) }
    fun setNaturalScroll(natural: Boolean) = viewModelScope.launch { repository.setNaturalScroll(natural) }
    fun setDark(dark: Boolean) = viewModelScope.launch { repository.setDark(dark) }
    fun setExtremeDark(enabled: Boolean) = viewModelScope.launch { repository.setExtremeDark(enabled) }
    fun setHaptics(enabled: Boolean) = viewModelScope.launch { repository.setHaptics(enabled) }
    fun setScrollHaptics(enabled: Boolean) = viewModelScope.launch { repository.setScrollHaptics(enabled) }
    fun setAdvancedLaptopGestures(enabled: Boolean) {
        hid.setAdvancedProfile(enabled)
        viewModelScope.launch { repository.setAdvancedLaptopGestures(enabled) }
    }
    fun setPinchToZoom(enabled: Boolean) = viewModelScope.launch { repository.setPinchToZoom(enabled) }
    fun setMultiFingerAction(slot: MultiFingerSlot, value: MultiFingerAction) =
        viewModelScope.launch { repository.setMultiFingerAction(slot, value) }
    fun setDeveloperDiagnostics(enabled: Boolean) = viewModelScope.launch { repository.setDeveloperDiagnostics(enabled) }
    fun setKeepAwake(enabled: Boolean) = viewModelScope.launch { repository.setKeepAwake(enabled) }
    fun setMouseButton(button: Int, down: Boolean) {
        hid.setButton(button, down)
        if (down) haptic(HapticCue.CLICK)
    }
    fun media(control: ConsumerControl) {
        hid.consumer(control)
        haptic(HapticCue.SHORTCUT)
    }
    fun adjustVolume(control: ConsumerControl) {
        hid.volume(control)
        haptic(HapticCue.VOLUME_STEP)
    }
    /** Mirrors edits from the phone IME to the focused Windows control. */
    fun typeFromPhone(previous: String, next: String) {
        if (!settings.value.advancedLaptopGestures || !isConnected()) return
        var prefix = 0
        val prefixLimit = minOf(previous.length, next.length)
        while (prefix < prefixLimit && previous[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (
            suffix < previous.length - prefix && suffix < next.length - prefix &&
            previous[previous.length - suffix - 1] == next[next.length - suffix - 1]
        ) suffix++

        val removed = previous.substring(prefix, previous.length - suffix)
        repeat(removed.count { HidReportEncoder.keyboardUsage(it) != null }) {
            hid.key(0, HidReportEncoder.HID_BACKSPACE)
        }
        next.substring(prefix, next.length - suffix).forEach { character ->
            HidReportEncoder.keyboardUsage(character)?.let { usage -> hid.key(usage.modifiers, usage.key) }
        }
    }
    fun isConnected() = connection.value is ConnectionState.Connected
    fun handleVolumeKey(event: KeyEvent): Boolean {
        val s = settings.value
        if (!s.useVolumeShortcuts || !isConnected()) return false
        val action = when (event.keyCode) { KeyEvent.KEYCODE_VOLUME_UP -> s.volumeUp; KeyEvent.KEYCODE_VOLUME_DOWN -> s.volumeDown; else -> return false }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) execute(action)
        return true // consume key up too so Android does not change phone volume
    }
    private fun togglePrecision() { pointer.precisionMode = !pointer.precisionMode }
    fun toggleDragLock() {
        val enabled = !_dragLocked.value
        _dragLocked.value = enabled
        hid.setButton(HidReportEncoder.LEFT, enabled)
        haptic(if (enabled) HapticCue.DRAG_START else HapticCue.CLICK)
    }
    override fun move(dx: Float, dy: Float) { val (x, y) = pointer.move(dx, dy); if (x != 0 || y != 0) hid.move(x, y) }
    override fun scroll(delta: ScrollDelta) {
        val settings = settings.value
        val (v, h) = scroll.scroll(delta.dx, delta.dy, settings.naturalScroll)
        if (v == 0 && h == 0) return
        hid.scroll(v, h)
        val now = SystemClock.uptimeMillis()
        if (settings.haptics && settings.scrollHaptics && now - lastScrollHapticAt >= SCROLL_HAPTIC_INTERVAL_MS) {
            lastScrollHapticAt = now
            _hapticEvents.tryEmit(HapticCue.SCROLL)
        }
    }
    override fun finishMotion() {
        pinchAccumulator = 0f
        multiFingerVolumeDistance = 0f
        activeMultiFingerSlot = null
        hid.discardPendingMotion()
    }
    override fun mouseDown() { hid.setButton(HidReportEncoder.LEFT, true); haptic(HapticCue.DRAG_START) }
    override fun mouseUp() = hid.setButton(HidReportEncoder.LEFT, false)
    override fun click(count: Int, button: MouseButton, isDoubleTap: Boolean) {
        val hidButton = when (button) { MouseButton.LEFT -> HidReportEncoder.LEFT; MouseButton.RIGHT -> HidReportEncoder.RIGHT; MouseButton.MIDDLE -> HidReportEncoder.MIDDLE }
        repeat(count) { hid.click(hidButton) }
        haptic(if (isDoubleTap) HapticCue.DOUBLE_CLICK else HapticCue.CLICK)
    }
    override fun swipe(fingers: Int, dx: Float, dy: Float, isContinuation: Boolean) {
        if (!settings.value.advancedLaptopGestures) return
        val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy)
        val detectedSlot = when (fingers) {
            3 -> if (horizontal) if (dx < 0) MultiFingerSlot.THREE_LEFT else MultiFingerSlot.THREE_RIGHT else if (dy < 0) MultiFingerSlot.THREE_UP else MultiFingerSlot.THREE_DOWN
            4 -> if (horizontal) if (dx < 0) MultiFingerSlot.FOUR_LEFT else MultiFingerSlot.FOUR_RIGHT else if (dy < 0) MultiFingerSlot.FOUR_UP else MultiFingerSlot.FOUR_DOWN
            5 -> if (horizontal) if (dx < 0) MultiFingerSlot.FIVE_LEFT else MultiFingerSlot.FIVE_RIGHT else if (dy < 0) MultiFingerSlot.FIVE_UP else MultiFingerSlot.FIVE_DOWN
            else -> return
        }
        // Lock the direction after activation. Tiny sideways jitter must not
        // turn a continuous vertical volume gesture into a different binding.
        val slot = if (isContinuation) activeMultiFingerSlot ?: detectedSlot else detectedSlot.also { activeMultiFingerSlot = it }
        val action = settings.value.multiFingerBindings[slot]
        if (action == MultiFingerAction.VOLUME_UP || action == MultiFingerAction.VOLUME_DOWN) {
            if (!isContinuation) multiFingerVolumeDistance = 0f
            multiFingerVolumeDistance += if (horizontal) kotlin.math.abs(dx) else kotlin.math.abs(dy)
            val steps = (multiFingerVolumeDistance / multiFingerVolumeStepDistance).toInt()
            if (steps > 0) {
                repeat(steps) {
                    adjustVolume(if (action == MultiFingerAction.VOLUME_UP) ConsumerControl.VOLUME_UP else ConsumerControl.VOLUME_DOWN)
                }
                multiFingerVolumeDistance -= steps * multiFingerVolumeStepDistance
            }
        } else if (!isContinuation) {
            performMultiFingerAction(action)
        }
    }
    override fun multiFingerTap(fingers: Int) {
        if (!settings.value.advancedLaptopGestures) return
        val slot = when (fingers) {
            3 -> MultiFingerSlot.THREE_TAP
            4 -> MultiFingerSlot.FOUR_TAP
            5 -> MultiFingerSlot.FIVE_TAP
            else -> return
        }
        performMultiFingerAction(settings.value.multiFingerBindings[slot])
    }
    override fun pinch(scaleDelta: Float) {
        if (!settings.value.advancedLaptopGestures || !settings.value.pinchToZoom) return
        pinchAccumulator += scaleDelta
        while (pinchAccumulator >= PINCH_STEP) {
            hid.zoomKey(HID_LEFT_CONTROL, HID_EQUAL)
            pinchAccumulator -= PINCH_STEP
        }
        while (pinchAccumulator <= -PINCH_STEP) {
            hid.zoomKey(HID_LEFT_CONTROL, HID_MINUS)
            pinchAccumulator += PINCH_STEP
        }
    }
    override fun cancel() = releaseInputs()
    private fun performMultiFingerAction(action: MultiFingerAction) = when (action) {
        MultiFingerAction.NONE -> Unit
        MultiFingerAction.TASK_VIEW -> execute(DeskAction.TASK_VIEW)
        MultiFingerAction.SHOW_DESKTOP -> execute(DeskAction.SHOW_DESKTOP)
        MultiFingerAction.SWITCH_APP_PREVIOUS -> execute(DeskAction.SWITCH_APPS_BACK)
        MultiFingerAction.SWITCH_APP_NEXT -> execute(DeskAction.SWITCH_APPS)
        MultiFingerAction.DESKTOP_LEFT -> execute(DeskAction.DESKTOP_LEFT)
        MultiFingerAction.DESKTOP_RIGHT -> execute(DeskAction.DESKTOP_RIGHT)
        MultiFingerAction.SEARCH -> execute(DeskAction.SEARCH)
        MultiFingerAction.VOLUME_UP -> adjustVolume(ConsumerControl.VOLUME_UP)
        MultiFingerAction.VOLUME_DOWN -> adjustVolume(ConsumerControl.VOLUME_DOWN)
        MultiFingerAction.MUTE -> media(ConsumerControl.MUTE)
        MultiFingerAction.TRACK_PREVIOUS -> media(ConsumerControl.PREVIOUS)
        MultiFingerAction.TRACK_NEXT -> media(ConsumerControl.NEXT)
        MultiFingerAction.PLAY_PAUSE -> media(ConsumerControl.PLAY_PAUSE)
    }
    private fun haptic(cue: HapticCue) { if (settings.value.haptics) _hapticEvents.tryEmit(cue) }

    private companion object {
        const val SCROLL_HAPTIC_INTERVAL_MS = 45L
        const val PINCH_STEP = .10f
        const val HID_LEFT_CONTROL = 0x01
        const val HID_MINUS = 0x2D
        const val HID_EQUAL = 0x2E
    }
}
