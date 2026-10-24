package com.deskpilot.hid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

sealed interface ConnectionState {
    data object Starting : ConnectionState
    data object PermissionRequired : ConnectionState
    data object Unsupported : ConnectionState
    data object Registering : ConnectionState
    data object Ready : ConnectionState
    data class Connecting(val name: String) : ConnectionState
    data class Connected(val name: String) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class HidDiagnostics(
    val reportsPerSecond: Int = 0,
    val heldButtons: Int = 0,
    val queuedShortcuts: Int = 0,
    val failedReports: Int = 0
)

enum class ConsumerControl(val bit: Int) {
    MUTE(HidReportEncoder.CONSUMER_MUTE),
    VOLUME_UP(HidReportEncoder.CONSUMER_VOLUME_UP),
    VOLUME_DOWN(HidReportEncoder.CONSUMER_VOLUME_DOWN),
    NEXT(HidReportEncoder.CONSUMER_NEXT),
    PREVIOUS(HidReportEncoder.CONSUMER_PREVIOUS),
    PLAY_PAUSE(HidReportEncoder.CONSUMER_PLAY_PAUSE)
}

/**
 * The deliberately small Bluetooth HID transport used by DeskPilot.
 *
 * A Bluetooth bond is not enough to accept mouse reports. `channelOpen` is only
 * set by Android's HID connection callback, so the UI cannot claim that input is
 * active merely because Windows has retained a Bluetooth connection.
 */
class HidDeviceManager(private val context: Context) {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var channelOpen = false
    private var buttons = 0
    private var registered = false
    private var profileRequestGeneration = 0
    private var protocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE
    private var reconnectAfterClose: BluetoothDevice? = null
    private var advancedProfile = false
    private var profileRefreshPending = false
    private var profileRefreshScheduled = false
    private var connectingDevice: BluetoothDevice? = null
    private var recoveryAttempted = false
    private val reportHandler = Handler(Looper.getMainLooper())
    private val pendingClickReleases = mutableMapOf<Int, Runnable>()
    private enum class KeySource { USER, GESTURE_ZOOM }
    private data class KeyStroke(val report: ByteArray, val source: KeySource)
    private val pendingKeyStrokes = ArrayDeque<KeyStroke>()
    private var keyStrokeInFlight = false
    private var activeKeySource: KeySource? = null
    private val pendingConsumerControls = ArrayDeque<Int>()
    private var consumerInFlight = false
    private val connectionPrefs: SharedPreferences =
        context.getSharedPreferences("deskpilot_hid_connection", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Starting)
    val state: StateFlow<ConnectionState> = _state
    private val _diagnostics = MutableStateFlow(HidDiagnostics())
    val diagnostics: StateFlow<HidDiagnostics> = _diagnostics
    private var diagnosticsEnabled = false
    private var reportsInWindow = 0
    private var reportWindowStartedAt = 0L
    private var reportsPerSecond = 0
    private var failedReports = 0
    private val executor: Executor = context.mainExecutor
    private val keyStrokeReleaseRunnable = Runnable { releaseCurrentKeyStroke() }
    private val keyStrokeNextRunnable = Runnable {
        keyStrokeInFlight = false
        activeKeySource = null
        sendNextKeyStroke()
    }
    private val consumerReleaseRunnable = Runnable { releaseCurrentConsumerControl() }
    private val consumerNextRunnable = Runnable {
        consumerInFlight = false
        sendNextConsumerControl()
    }
    private val connectionTimeoutRunnable = Runnable { recoverStuckConnection() }
    private val reconnectFallbackRunnable = Runnable { retryConnectionAfterClose() }

    init {
        advancedProfile = connectionPrefs.getBoolean(ADVANCED_PROFILE, false)
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidDeviceManager.registered = registered
            if (!registered) {
                cancelAllClickReleases()
                clearPendingKeyStrokes()
                clearPendingConsumerControls()
                buttons = 0
                channelOpen = false
                host = null
                clearConnectionAttempt()
                if (profileRefreshPending) {
                    scheduleProfileRegistration()
                    return
                }
                _state.value = ConnectionState.Error("DeskPilot is no longer registered as a Bluetooth HID device")
                return
            }
            profileRefreshPending = false
            if (pluggedDevice == null) {
                host = null
                channelOpen = false
                val next = reconnectAfterClose
                if (next != null) {
                    // Closing a stale cable reports an unplug before (or instead
                    // of) the normal disconnected callback on some phones.
                    markConnecting(next)
                    scheduleReconnectFallback(next)
                } else {
                    clearConnectionAttempt()
                    _state.value = ConnectionState.Ready
                }
            } else {
                // This is not proof that the interrupt/input channel is ready.
                if (channelOpen && host?.address == pluggedDevice.address) return
                host = pluggedDevice
                channelOpen = false
                rememberHost(pluggedDevice)
                markConnecting(pluggedDevice)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    host = device
                    channelOpen = false
                    markConnecting(device)
                }

                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    channelOpen = true
                    protocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE
                    reconnectAfterClose = null
                    rememberHost(device)
                    clearConnectionAttempt()
                    _state.value = ConnectionState.Connected(device.name ?: device.address)
                    // If Windows rejects this first neutral report, sendNow changes
                    // the state to Error. Do not overwrite that failure below.
                    resetButtonsAndSendNeutral()
                    Log.i(TAG, "HID input channel opened for ${device.address}")
                }

                BluetoothProfile.STATE_DISCONNECTING -> {
                    if (host?.address == device.address) {
                        channelOpen = false
                        _state.value = ConnectionState.Connecting(device.name ?: device.address)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host?.address != device.address && reconnectAfterClose?.address != device.address) return
                    channelOpen = false
                    host = null
                    cancelAllClickReleases()
                    clearPendingKeyStrokes()
                    clearPendingConsumerControls()
                    buttons = 0
                    val next = reconnectAfterClose
                    reconnectAfterClose = null
                    if (next != null && registered) {
                        markConnecting(next)
                        val accepted = try {
                            hid?.connect(next) == true
                        } catch (error: RuntimeException) {
                            Log.e(TAG, "Could not reconnect Bluetooth HID", error)
                            false
                        }
                        if (!accepted) scheduleReconnectFallback(next)
                    } else {
                        clearConnectionAttempt()
                        _state.value = if (registered) ConnectionState.Ready else ConnectionState.Error("Bluetooth HID disconnected")
                    }
                }
            }
        }

        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            val currentHost = host
            if (currentHost != null && currentHost.address != device.address) return
            this@HidDeviceManager.protocol = protocol
            Log.i(TAG, "HID protocol for ${device.address}: ${if (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) "boot" else "report"}")
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val report = when {
                protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE && id.toInt() == HidReportEncoder.BASIC_MOUSE_REPORT_ID ->
                    HidReportEncoder.bootMouse(buttons, 0, 0)
                id.toInt() == mouseReportId() -> currentReport(0, 0, 0, 0)
                advancedProfile && id.toInt() == HidReportEncoder.KEYBOARD_REPORT_ID -> HidReportEncoder.keyboard(0)
                advancedProfile && id.toInt() == HidReportEncoder.CONSUMER_REPORT_ID -> HidReportEncoder.consumer(0)
                else -> return
            }
            val reply = report.copyOf(maxOf(report.size, bufferSize))
            if (hid?.replyReport(device, type, id, reply) != true) {
                Log.w(TAG, "Could not reply to HID GET_REPORT for ${device.address}")
            }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            if (host?.address == device.address) {
                channelOpen = false
                host = null
                buttons = 0
                cancelAllClickReleases()
                clearPendingKeyStrokes()
                clearPendingConsumerControls()
                clearConnectionAttempt()
                _state.value = ConnectionState.Ready
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            profileRequestGeneration++
            hid = proxy as? BluetoothHidDevice
            if (hid == null) {
                _state.value = ConnectionState.Error("This phone returned an invalid Bluetooth HID service")
                return
            }
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
            channelOpen = false
            buttons = 0
            cancelAllClickReleases()
            clearPendingKeyStrokes()
            clearPendingConsumerControls()
            clearConnectionAttempt()
            _state.value = ConnectionState.Error("Bluetooth HID service stopped")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(hasPermissions: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            _state.value = ConnectionState.Unsupported
            return
        }
        if (!hasPermissions) {
            _state.value = ConnectionState.PermissionRequired
            return
        }
        if (adapter == null || !adapter.isEnabled) {
            _state.value = ConnectionState.Error("Turn on Bluetooth to use DeskPilot")
            return
        }
        if (hid != null) {
            if (!registered) register()
            return
        }
        _state.value = ConnectionState.Registering
        val generation = ++profileRequestGeneration
        val requested = try {
            adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (error: SecurityException) {
            reportBluetoothFailure("Nearby devices permission is required", error)
            return
        } catch (error: RuntimeException) {
            reportBluetoothFailure("This phone could not open Bluetooth HID", error)
            return
        }
        if (!requested) {
            _state.value = ConnectionState.Unsupported
            return
        }
        reportHandler.postDelayed({
            if (generation == profileRequestGeneration && hid == null) {
                _state.value = ConnectionState.Error("This phone does not provide Android Bluetooth HID device mode")
            }
        }, PROFILE_CONNECTION_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun register() {
        val accepted = try {
            hid?.registerApp(
                BluetoothHidDeviceAppSdpSettings(
                    "DeskPilot",
                    if (advancedProfile) "Advanced Bluetooth trackpad" else "Simple Bluetooth trackpad",
                    "DeskPilot",
                    if (advancedProfile) BluetoothHidDevice.SUBCLASS1_COMBO else BluetoothHidDevice.SUBCLASS1_MOUSE,
                    if (advancedProfile) HidDescriptor.advancedBytes else HidDescriptor.basicBytes
                ),
                null,
                null,
                executor,
                callback
            ) == true
        } catch (error: SecurityException) {
            reportBluetoothFailure("Bluetooth permission was revoked", error)
            return
        } catch (error: RuntimeException) {
            reportBluetoothFailure("This phone could not register Bluetooth HID", error)
            return
        }
        if (!accepted) _state.value = ConnectionState.Error("Could not register the Bluetooth HID device")
    }

    fun move(dx: Int, dy: Int) = sendMouse(dx, dy, 0, 0)
    fun scroll(vertical: Int, horizontal: Int) {
        if (vertical != 0 || horizontal != 0) sendMouse(0, 0, vertical, if (advancedProfile) horizontal else 0)
    }

    fun setButton(button: Int, down: Boolean) {
        cancelPendingClickRelease(button)
        buttons = if (down) buttons or button else buttons and button.inv()
        sendMouse(0, 0, 0, 0)
        publishDiagnostics()
    }

    fun click(button: Int) {
        cancelPendingClickRelease(button)
        buttons = buttons or button
        sendMouse(0, 0, 0, 0)
        val release = Runnable {
            pendingClickReleases.remove(button)
            buttons = buttons and button.inv()
            sendMouse(0, 0, 0, 0)
        }
        pendingClickReleases[button] = release
        reportHandler.postDelayed(release, CLICK_HOLD_MS)
    }

    fun releaseAll() {
        cancelAllClickReleases()
        clearPendingKeyStrokes(releaseActiveKey = true)
        clearPendingConsumerControls(releaseActiveControl = true)
        buttons = 0
        sendMouse(0, 0, 0, 0)
        publishDiagnostics()
    }

    /** Keyboard reports exist only in the optional advanced profile. */
    fun key(modifiers: Int, vararg keys: Int) {
        if (!advancedProfile || !channelOpen || protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) return
        pendingKeyStrokes.addLast(KeyStroke(HidReportEncoder.keyboard(modifiers, *keys), KeySource.USER))
        sendNextKeyStroke()
    }

    /** Gesture zoom is independently cancellable and cannot drop user shortcuts. */
    fun zoomKey(modifiers: Int, vararg keys: Int) {
        if (!advancedProfile || !channelOpen || protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) return
        pendingKeyStrokes.addLast(KeyStroke(HidReportEncoder.keyboard(modifiers, *keys), KeySource.GESTURE_ZOOM))
        sendNextKeyStroke()
    }

    /** Sends one standard Windows media/volume action through Consumer Control. */
    fun consumer(control: ConsumerControl) {
        if (!advancedProfile || !channelOpen || protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) return
        pendingConsumerControls.addLast(control.bit)
        sendNextConsumerControl()
    }

    fun cancelPendingZoom() {
        if (pendingKeyStrokes.isNotEmpty()) {
            val retained = ArrayDeque<KeyStroke>()
            while (pendingKeyStrokes.isNotEmpty()) {
                val stroke = pendingKeyStrokes.removeFirst()
                if (stroke.source != KeySource.GESTURE_ZOOM) retained.addLast(stroke)
            }
            pendingKeyStrokes.addAll(retained)
        }
        if (activeKeySource == KeySource.GESTURE_ZOOM) {
            reportHandler.removeCallbacks(keyStrokeReleaseRunnable)
            reportHandler.removeCallbacks(keyStrokeNextRunnable)
            sendKeyboardNeutral()
            keyStrokeInFlight = false
            activeKeySource = null
            sendNextKeyStroke()
        }
    }

    fun setDiagnosticsEnabled(enabled: Boolean) {
        diagnosticsEnabled = enabled
        publishDiagnostics()
    }

    fun discardPendingMotion() = cancelPendingZoom()

    private fun sendMouse(dx: Int, dy: Int, wheel: Int, pan: Int) {
        val device = host ?: return
        if (!channelOpen) return
        sendNow(device, mouseReportId(), currentReport(dx, dy, wheel, pan))
    }

    private fun mouseReportId() = if (advancedProfile && protocol != BluetoothHidDevice.PROTOCOL_BOOT_MODE) {
        HidReportEncoder.ADVANCED_MOUSE_REPORT_ID
    } else HidReportEncoder.BASIC_MOUSE_REPORT_ID

    private fun currentReport(dx: Int, dy: Int, wheel: Int, pan: Int): ByteArray =
        if (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) {
            HidReportEncoder.bootMouse(buttons, dx, dy)
        } else if (advancedProfile) {
            HidReportEncoder.advancedMouse(buttons, dx, dy, wheel, pan)
        } else {
            HidReportEncoder.mouse(buttons, dx, dy, wheel)
        }

    /**
     * Windows can drop or reorder overlapping keyboard press/release reports.
     * Keep pinch zoom shortcuts in one short, evenly spaced sequence instead.
     */
    private fun sendNextKeyStroke() {
        if (keyStrokeInFlight || pendingKeyStrokes.isEmpty()) return
        val device = host
        if (device == null || !advancedProfile || !channelOpen || protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) {
            clearPendingKeyStrokes()
            return
        }
        keyStrokeInFlight = true
        val stroke = pendingKeyStrokes.removeFirst()
        activeKeySource = stroke.source
        sendNow(device, HidReportEncoder.KEYBOARD_REPORT_ID, stroke.report)
        reportHandler.postDelayed(keyStrokeReleaseRunnable, KEY_HOLD_MS)
    }

    private fun releaseCurrentKeyStroke() {
        sendKeyboardNeutral()
        reportHandler.postDelayed(keyStrokeNextRunnable, KEY_GAP_MS)
    }

    private fun clearPendingKeyStrokes(releaseActiveKey: Boolean = false) {
        val device = host
        val shouldRelease = releaseActiveKey && keyStrokeInFlight && device != null && advancedProfile &&
            channelOpen && protocol != BluetoothHidDevice.PROTOCOL_BOOT_MODE
        pendingKeyStrokes.clear()
        keyStrokeInFlight = false
        activeKeySource = null
        reportHandler.removeCallbacks(keyStrokeReleaseRunnable)
        reportHandler.removeCallbacks(keyStrokeNextRunnable)
        if (shouldRelease && device != null) {
            sendNow(device, HidReportEncoder.KEYBOARD_REPORT_ID, HidReportEncoder.keyboard(0))
        }
    }

    private fun sendNextConsumerControl() {
        if (consumerInFlight || pendingConsumerControls.isEmpty()) return
        val device = host
        if (device == null || !advancedProfile || !channelOpen || protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) {
            clearPendingConsumerControls()
            return
        }
        consumerInFlight = true
        val control = pendingConsumerControls.removeFirst()
        sendNow(device, HidReportEncoder.CONSUMER_REPORT_ID, HidReportEncoder.consumer(control))
        reportHandler.postDelayed(consumerReleaseRunnable, CONSUMER_HOLD_MS)
    }

    private fun releaseCurrentConsumerControl() {
        val device = host
        if (device != null && advancedProfile && channelOpen && protocol != BluetoothHidDevice.PROTOCOL_BOOT_MODE) {
            sendNow(device, HidReportEncoder.CONSUMER_REPORT_ID, HidReportEncoder.consumer(0))
        }
        reportHandler.postDelayed(consumerNextRunnable, CONSUMER_GAP_MS)
    }

    private fun clearPendingConsumerControls(releaseActiveControl: Boolean = false) {
        val device = host
        val shouldRelease = releaseActiveControl && consumerInFlight && device != null && advancedProfile &&
            channelOpen && protocol != BluetoothHidDevice.PROTOCOL_BOOT_MODE
        pendingConsumerControls.clear()
        consumerInFlight = false
        reportHandler.removeCallbacks(consumerReleaseRunnable)
        reportHandler.removeCallbacks(consumerNextRunnable)
        if (shouldRelease && device != null) {
            sendNow(device, HidReportEncoder.CONSUMER_REPORT_ID, HidReportEncoder.consumer(0))
        }
    }

    private fun sendKeyboardNeutral() {
        val device = host
        if (device != null && advancedProfile && channelOpen && protocol != BluetoothHidDevice.PROTOCOL_BOOT_MODE) {
            sendNow(device, HidReportEncoder.KEYBOARD_REPORT_ID, HidReportEncoder.keyboard(0))
        }
    }

    private fun resetButtonsAndSendNeutral() {
        cancelAllClickReleases()
        buttons = 0
        sendMouse(0, 0, 0, 0)
    }

    private fun sendNow(device: BluetoothDevice, reportId: Int, data: ByteArray): Boolean {
        val accepted = try {
            hid?.sendReport(device, reportId, data) == true
        } catch (error: RuntimeException) {
            reportBluetoothFailure("This phone rejected the HID input report", error)
            recordReport(accepted = false)
            return false
        }
        recordReport(accepted)
        if (accepted) return true
        channelOpen = false
        _state.value = ConnectionState.Error("Windows is not accepting DeskPilot mouse reports")
        return false
    }

    private fun recordReport(accepted: Boolean) {
        if (!diagnosticsEnabled) return
        val now = SystemClock.uptimeMillis()
        if (reportWindowStartedAt == 0L) reportWindowStartedAt = now
        if (now - reportWindowStartedAt >= REPORT_RATE_WINDOW_MS) {
            reportsPerSecond = (reportsInWindow * 1_000L / (now - reportWindowStartedAt)).toInt()
            reportsInWindow = 0
            reportWindowStartedAt = now
        }
        if (accepted) reportsInWindow++ else failedReports++
        publishDiagnostics()
    }

    private fun publishDiagnostics() {
        if (!diagnosticsEnabled) return
        _diagnostics.value = HidDiagnostics(
            reportsPerSecond = reportsPerSecond,
            heldButtons = buttons,
            queuedShortcuts = pendingKeyStrokes.size + pendingConsumerControls.size +
                (if (keyStrokeInFlight) 1 else 0) + (if (consumerInFlight) 1 else 0),
            failedReports = failedReports
        )
    }

    /**
     * Changes profile only after explicit user confirmation. Windows must be
     * paired again because it caches the HID descriptor for each Bluetooth bond.
     */
    @SuppressLint("MissingPermission")
    fun setAdvancedProfile(enabled: Boolean) {
        if (advancedProfile == enabled) return
        val service = hid
        if (service != null && registered) releaseAll()
        advancedProfile = enabled
        connectionPrefs.edit().putBoolean(ADVANCED_PROFILE, enabled).apply()
        if (service == null || !registered) return
        profileRefreshPending = true
        registered = false
        channelOpen = false
        host = null
        _state.value = ConnectionState.Registering
        try {
            service.unregisterApp()
        } catch (error: SecurityException) {
            profileRefreshPending = false
            reportBluetoothFailure("Nearby devices permission is required", error)
            return
        } catch (error: RuntimeException) {
            profileRefreshPending = false
            reportBluetoothFailure("This phone could not change the HID profile", error)
            return
        }
        scheduleProfileRegistration()
    }

    private fun scheduleProfileRegistration() {
        if (profileRefreshScheduled) return
        profileRefreshScheduled = true
        reportHandler.postDelayed({
            profileRefreshScheduled = false
            if (profileRefreshPending && hid != null && !registered) register()
        }, PROFILE_REFRESH_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    fun rememberedDevices(): List<BluetoothDevice> = try {
        adapter?.bondedDevices?.toList().orEmpty()
    } catch (error: SecurityException) {
        reportBluetoothFailure("Nearby devices permission is required", error)
        emptyList()
    }

    /** Starts one HID virtual cable; it is not reported as Connected until the callback arrives. */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        val service = hid
        if (!registered || service == null) {
            _state.value = ConnectionState.Error("Enable DeskPilot Bluetooth before connecting")
            return false
        }
        rememberHost(device)
        val existingState = try {
            service.getConnectionState(device)
        } catch (error: SecurityException) {
            reportBluetoothFailure("Nearby devices permission is required", error)
            return false
        } catch (error: RuntimeException) {
            reportBluetoothFailure("This phone could not inspect the HID connection", error)
            return false
        }
        if (existingState == BluetoothProfile.STATE_CONNECTED) return restartVirtualCable(service, device)
        markConnecting(device, freshRequest = true)
        val accepted = try {
            service.connect(device)
        } catch (error: SecurityException) {
            reportBluetoothFailure("Nearby devices permission is required", error)
            return false
        } catch (error: RuntimeException) {
            reportBluetoothFailure("This phone could not start the HID connection", error)
            return false
        }
        if (!accepted) scheduleReconnectFallback(device)
        return accepted
    }

    @SuppressLint("MissingPermission")
    fun reconnect(): Boolean {
        val device = host ?: lastRememberedHost()
        val service = hid
        if (!registered || service == null || device == null) {
            _state.value = ConnectionState.Error("No remembered computer to reconnect")
            return false
        }
        return restartVirtualCable(service, device)
    }

    @SuppressLint("MissingPermission")
    private fun restartVirtualCable(service: BluetoothHidDevice, device: BluetoothDevice): Boolean {
        releaseAll()
        markConnecting(device, freshRequest = true)
        reconnectAfterClose = device
        val accepted = try {
            service.disconnect(device)
        } catch (error: SecurityException) {
            reconnectAfterClose = null
            reportBluetoothFailure("Nearby devices permission is required", error)
            return false
        } catch (error: RuntimeException) {
            reconnectAfterClose = null
            reportBluetoothFailure("This phone could not restart the HID connection", error)
            return false
        }
        // Android can report a successful close without delivering its close
        // callback after Windows sleep. The fallback is harmless when that
        // callback arrives, and avoids another long Connecting wait when it does not.
        if (!accepted) Log.w(TAG, "HID cable close was not accepted for ${device.address}")
        scheduleReconnectFallback(device)
        return accepted
    }

    private fun markConnecting(device: BluetoothDevice, freshRequest: Boolean = false) {
        if (freshRequest || connectingDevice?.address != device.address) recoveryAttempted = false
        connectingDevice = device
        host = device
        channelOpen = false
        _state.value = ConnectionState.Connecting(device.name ?: device.address)
        reportHandler.removeCallbacks(connectionTimeoutRunnable)
        reportHandler.postDelayed(connectionTimeoutRunnable, HID_CHANNEL_TIMEOUT_MS)
    }

    private fun clearConnectionAttempt() {
        connectingDevice = null
        recoveryAttempted = false
        reportHandler.removeCallbacks(connectionTimeoutRunnable)
        reportHandler.removeCallbacks(reconnectFallbackRunnable)
    }

    /** Resets one stale HID virtual cable instead of leaving CONNECTING forever after a PC resume. */
    @SuppressLint("MissingPermission")
    private fun recoverStuckConnection() {
        val device = connectingDevice ?: return
        if (channelOpen || host?.address != device.address || _state.value !is ConnectionState.Connecting) return
        if (recoveryAttempted) {
            reconnectAfterClose = null
            clearConnectionAttempt()
            _state.value = ConnectionState.Error("Mouse input did not reopen. Tap Reconnect mouse input.")
            return
        }
        recoveryAttempted = true
        reconnectAfterClose = device
        val disconnected = try {
            hid?.disconnect(device) == true
        } catch (error: SecurityException) {
            reportBluetoothFailure("Nearby devices permission is required", error)
            false
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not close stale Bluetooth HID connection", error)
            false
        }
        Log.i(TAG, "HID connection timed out for ${device.address}; resetting virtual cable (disconnect=$disconnected)")
        scheduleReconnectFallback(device)
    }

    private fun scheduleReconnectFallback(device: BluetoothDevice) {
        reconnectAfterClose = device
        reportHandler.removeCallbacks(reconnectFallbackRunnable)
        reportHandler.postDelayed(reconnectFallbackRunnable, HID_RECONNECT_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun retryConnectionAfterClose() {
        val device = reconnectAfterClose ?: return
        if (channelOpen) return
        reconnectAfterClose = null
        val service = hid
        if (!registered || service == null) {
            clearConnectionAttempt()
            _state.value = ConnectionState.Error("DeskPilot Bluetooth is not ready")
            return
        }
        markConnecting(device)
        val accepted = try {
            service.connect(device)
        } catch (error: SecurityException) {
            reportBluetoothFailure("Nearby devices permission is required", error)
            false
        } catch (error: RuntimeException) {
            reportBluetoothFailure("This phone could not reopen the HID mouse input", error)
            false
        }
        if (!accepted) Log.w(TAG, "HID reconnect request was not accepted for ${device.address}")
    }

    @SuppressLint("MissingPermission")
    private fun lastRememberedHost(): BluetoothDevice? {
        val address = connectionPrefs.getString(LAST_HOST_ADDRESS, null) ?: return null
        return try {
            adapter?.bondedDevices?.firstOrNull { it.address == address }
        } catch (error: SecurityException) {
            reportBluetoothFailure("Nearby devices permission is required", error)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun rememberHost(device: BluetoothDevice) {
        connectionPrefs.edit().putString(LAST_HOST_ADDRESS, device.address).apply()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        releaseAll()
        reportHandler.removeCallbacksAndMessages(null)
        reconnectAfterClose = null
        profileRefreshPending = false
        profileRefreshScheduled = false
        host = null
        channelOpen = false
        hid?.unregisterApp()
        hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hid = null
    }

    private fun cancelPendingClickRelease(button: Int) {
        pendingClickReleases.remove(button)?.let(reportHandler::removeCallbacks)
    }

    private fun cancelAllClickReleases() {
        pendingClickReleases.values.forEach(reportHandler::removeCallbacks)
        pendingClickReleases.clear()
    }

    private fun reportBluetoothFailure(message: String, error: Exception) {
        Log.e(TAG, message, error)
        _state.value = ConnectionState.Error("$message. Check Bluetooth permissions and try again.")
    }

    private companion object {
        const val LAST_HOST_ADDRESS = "last_host_address"
        const val TAG = "DeskPilotHid"
        const val ADVANCED_PROFILE = "advanced_profile"
        const val CLICK_HOLD_MS = 28L
        const val KEY_HOLD_MS = 12L
        const val KEY_GAP_MS = 8L
        const val CONSUMER_HOLD_MS = 24L
        const val CONSUMER_GAP_MS = 12L
        const val HID_CHANNEL_TIMEOUT_MS = 8_000L
        const val HID_RECONNECT_DELAY_MS = 650L
        const val PROFILE_CONNECTION_TIMEOUT_MS = 10_000L
        const val PROFILE_REFRESH_DELAY_MS = 350L
        const val REPORT_RATE_WINDOW_MS = 1_000L
    }
}
