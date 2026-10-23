package com.deskpilot

import android.app.Application
import android.os.Build
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
    enum class HapticCue { CLICK, DOUBLE_CLICK, DRAG_START, SHORTCUT }
    private val repository = SettingsRepository(app)
    private val hid = (app as DeskPilotApplication).hidDeviceManager
    val connection: StateFlow<ConnectionState> = hid.state
    val settings: StateFlow<DeskSettings> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeskSettings())
    private val pointer = PointerEngine()
    private val scroll = ScrollEngine()
    private val _dragLocked = MutableStateFlow(false)
    val dragLocked: StateFlow<Boolean> = _dragLocked.asStateFlow()
    private val _hapticEvents = MutableSharedFlow<HapticCue>(extraBufferCapacity = 4)
    val hapticEvents = _hapticEvents.asSharedFlow()
    private val actions = ActionRegistry(hid, ::togglePrecision, ::toggleDragLock)
    val gestures = GestureEngine(this)

    init { viewModelScope.launch { settings.collect { s -> pointer.updateTuning(PointerTuning(s.pointerSpeed, s.acceleration, s.accelerationStrength, s.precisionMultiplier)); scroll.updateSpeed(s.scrollSpeed) } } }
    fun startBluetooth(allowed: Boolean) {
        if (allowed) HidForegroundService.start(getApplication())
        hid.start(allowed)
    }
    fun close() { hid.stop(); HidForegroundService.stop(getApplication()) }
    fun releaseInputs() { _dragLocked.value = false; hid.releaseAll(); pointer.reset(); scroll.reset() }
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
    fun setNaturalScroll(natural: Boolean) = viewModelScope.launch { repository.setNaturalScroll(natural) }
    fun setDark(dark: Boolean) = viewModelScope.launch { repository.setDark(dark) }
    fun setExtremeDark(enabled: Boolean) = viewModelScope.launch { repository.setExtremeDark(enabled) }
    fun setHaptics(enabled: Boolean) = viewModelScope.launch { repository.setHaptics(enabled) }
    fun setKeepAwake(enabled: Boolean) = viewModelScope.launch { repository.setKeepAwake(enabled) }
    fun setMouseButton(button: Int, down: Boolean) {
        hid.setButton(button, down)
        if (down) haptic(HapticCue.CLICK)
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
    override fun scroll(dx: Float, dy: Float) { val (v, h) = scroll.scroll(dx, dy, settings.value.naturalScroll); if (v != 0 || h != 0) hid.scroll(v, h) }
    override fun finishMotion() = hid.discardPendingMotion()
    override fun mouseDown() { hid.setButton(HidReportEncoder.LEFT, true); haptic(HapticCue.DRAG_START) }
    override fun mouseUp() = hid.setButton(HidReportEncoder.LEFT, false)
    override fun click(count: Int, button: MouseButton, isDoubleTap: Boolean) {
        val hidButton = when (button) { MouseButton.LEFT -> HidReportEncoder.LEFT; MouseButton.RIGHT -> HidReportEncoder.RIGHT; MouseButton.MIDDLE -> HidReportEncoder.MIDDLE }
        repeat(count) { hid.click(hidButton) }
        haptic(if (isDoubleTap) HapticCue.DOUBLE_CLICK else HapticCue.CLICK)
    }
    override fun swipe(fingers: Int, dx: Float, dy: Float) {
        when (fingers) { 3 -> when { dy < 0 -> execute(DeskAction.TASK_VIEW); dy > 0 -> execute(DeskAction.SHOW_DESKTOP); dx < 0 -> execute(DeskAction.SWITCH_APPS_BACK); dx > 0 -> execute(DeskAction.SWITCH_APPS) }
            4 -> execute(if (dx < 0) DeskAction.DESKTOP_RIGHT else DeskAction.DESKTOP_LEFT) }
    }
    override fun pinch(scaleDelta: Float) { if (scaleDelta > 0f) hid.key(0x01, 0x2e) else hid.key(0x01, 0x2d) } // Ctrl +/-
    override fun cancel() = releaseInputs()
    private fun haptic(cue: HapticCue) { if (settings.value.haptics) _hapticEvents.tryEmit(cue) }
}
