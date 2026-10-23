package com.deskpilot.hid

import android.annotation.SuppressLint
import android.bluetooth.*
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

/** Owns the Android HID profile; gesture code never talks to Bluetooth directly. */
class HidDeviceManager(private val context: Context) {
    private val adapter = BluetoothAdapter.getDefaultAdapter()
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var buttons = 0
    private var registered = false
    private var profileRequestGeneration = 0
    private var protocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE
    private var reconnectAfterClose: BluetoothDevice? = null
    private var queuedDx = 0
    private var queuedDy = 0
    private var flushScheduled = false
    private var lastReportAtMs = 0L
    private val reportHandler = Handler(Looper.getMainLooper())
    private val connectionPrefs: SharedPreferences = context.getSharedPreferences("deskpilot_hid_connection", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Starting)
    val state: StateFlow<ConnectionState> = _state
    private val executor: Executor = context.mainExecutor

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidDeviceManager.registered = registered
            if (!registered) {
                _state.value = ConnectionState.Error("DeskPilot is no longer registered as a Bluetooth HID device")
                return
            }
            if (pluggedDevice != null) {
                host = pluggedDevice
                rememberHost(pluggedDevice)
                // A pluggedDevice can be left over while the host is restoring a
                // virtual cable. Wait for the real connection callback before
                // claiming that mouse reports are usable.
                _state.value = ConnectionState.Connecting(pluggedDevice.name ?: pluggedDevice.address)
            } else {
                _state.value = ConnectionState.Ready
            }
        }
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> _state.value = ConnectionState.Connecting(device.name ?: device.address)
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    protocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE
                    rememberHost(device)
                    _state.value = ConnectionState.Connected(device.name ?: device.address)
                    Log.i(TAG, "HID virtual cable opened for ${device.address}")
                    // Give the host a known initial mouse state as soon as its
                    // interrupt channel opens.
                    sendNow(device, HidReportEncoder.MOUSE_REPORT_ID, HidReportEncoder.mouse(0, 0, 0))
                }
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> if (host?.address == device.address || _state.value is ConnectionState.Connecting) {
                    host = null
                    buttons = 0
                    clearQueuedMotion()
                    val reconnect = reconnectAfterClose
                    reconnectAfterClose = null
                    if (reconnect != null && state == BluetoothProfile.STATE_DISCONNECTED && registered) {
                        _state.value = ConnectionState.Connecting(reconnect.name ?: reconnect.address)
                        if (hid?.connect(reconnect) != true) _state.value = ConnectionState.Ready
                    } else {
                        _state.value = if (registered) ConnectionState.Ready else ConnectionState.Error("Bluetooth HID disconnected")
                    }
                }
            }
        }

        /**
         * DeskPilot's baseline descriptor is boot compatible in both protocol
         * modes, so both Windows paths use the same report-ID-zero packet.
         */
        override fun onSetProtocol(device: BluetoothDevice, protocol: Byte) {
            val currentHost = host
            if (currentHost != null && currentHost.address != device.address) return
            this@HidDeviceManager.protocol = protocol
            Log.i(TAG, "HID protocol for ${device.address}: ${if (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) "boot" else "report"}")
        }

        /**
         * Windows can ask for the current input report immediately after restoring a
         * virtual-cable connection.  Without a reply the Bluetooth link looks
         * connected, but Windows may not finish bringing up its HID input channel.
         */
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            Log.i(TAG, "HID GET_REPORT type=$type id=$id size=$bufferSize from ${device.address}")
            val report = when (id.toInt()) {
                0 -> HidReportEncoder.mouse(buttons, 0, 0)
                else -> null
            }
            if (report == null) {
                Log.w(TAG, "Host requested unknown HID report id=$id")
                return
            }
            // The HID API asks for at least its requested buffer length. The padding
            // is harmless for a larger host request and avoids a malformed reply.
            val reply = report.copyOf(maxOf(report.size, bufferSize))
            if (hid?.replyReport(device, type, id, reply) != true) {
                Log.w(TAG, "Could not reply to HID GET_REPORT for ${device.address}")
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            Log.i(TAG, "HID SET_REPORT type=$type id=$id size=${data.size} from ${device.address}")
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            Log.i(TAG, "HID virtual cable unplugged by ${device.address}")
            if (host?.address == device.address) {
                host = null
                buttons = 0
                clearQueuedMotion()
                _state.value = ConnectionState.Ready
            }
        }
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            profileRequestGeneration++
            hid = proxy as BluetoothHidDevice
            register()
        }
        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
            buttons = 0
            _state.value = ConnectionState.Error("Bluetooth HID service stopped")
        }
    }

    @SuppressLint("MissingPermission")
    fun start(hasPermissions: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) { _state.value = ConnectionState.Unsupported; return }
        if (!hasPermissions) { _state.value = ConnectionState.PermissionRequired; return }
        if (adapter == null || !adapter.isEnabled) { _state.value = ConnectionState.Error("Turn on Bluetooth to use DeskPilot"); return }
        if (hid != null) {
            if (!registered) register()
            return
        }
        _state.value = ConnectionState.Registering
        val generation = ++profileRequestGeneration
        if (!adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)) {
            _state.value = ConnectionState.Unsupported
            return
        }
        // Some Android vendors expose Bluetooth HID host support but not the HID
        // device profile.  Do not leave those phones permanently on “Registering”.
        reportHandler.postDelayed({
            if (generation == profileRequestGeneration && hid == null) {
                _state.value = ConnectionState.Error(
                    "This phone does not provide Android Bluetooth HID device mode."
                )
            }
        }, PROFILE_CONNECTION_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun register() {
        val ok = hid?.registerApp(
            BluetoothHidDeviceAppSdpSettings("DeskPilot", "Wireless trackpad and mouse", "DeskPilot", BluetoothHidDevice.SUBCLASS1_MOUSE, HidDescriptor.bytes),
            null, null, executor, callback
        ) == true
        if (!ok) _state.value = ConnectionState.Error("Could not register the Bluetooth HID device")
    }

    fun move(dx: Int, dy: Int) = mouse(dx, dy)
    /**
     * Scrolling is deliberately disabled while DeskPilot establishes a reliable
     * boot-mouse connection.  A wheel axis changes the descriptor and caused the
     * Windows HID driver to fail on the affected laptop.
     */
    fun scroll(vertical: Int, horizontal: Int) {
        if (vertical != 0 || horizontal != 0) Log.d(TAG, "Scroll ignored in boot-mouse baseline")
    }
    fun setButton(button: Int, down: Boolean) {
        buttons = if (down) buttons or button else buttons and button.inv()
        mouse(0, 0, immediate = true)
    }
    fun click(button: Int) { setButton(button, true); setButton(button, false) }
    fun releaseAll() {
        buttons = 0
        mouse(0, 0, immediate = true)
    }
    fun key(modifiers: Int, vararg keys: Int) {
        Log.i(TAG, "Keyboard shortcut ignored in mouse compatibility mode")
    }
    private fun mouse(dx: Int, dy: Int, immediate: Boolean = false) {
        if (host == null) return
        queuedDx += dx
        queuedDy += dy
        if (immediate) {
            flushMotion()
            return
        }
        if (flushScheduled) return
        val delay = (MIN_REPORT_INTERVAL_MS - (SystemClock.uptimeMillis() - lastReportAtMs)).coerceAtLeast(0)
        flushScheduled = true
        reportHandler.postDelayed({ flushMotion() }, delay)
    }

    private fun flushMotion() {
        flushScheduled = false
        val currentHost = host ?: run { clearQueuedMotion(); return }
        val dx = takeAxis { queuedDx }.also { queuedDx -= it }
        val dy = takeAxis { queuedDy }.also { queuedDy -= it }
        sendNow(currentHost, HidReportEncoder.MOUSE_REPORT_ID, HidReportEncoder.mouse(buttons, dx, dy))
        lastReportAtMs = SystemClock.uptimeMillis()
        if (queuedDx != 0 || queuedDy != 0) mouse(0, 0)
    }

    /** Makes a stale virtual-cable connection visible instead of silently dropping input. */
    private fun sendNow(device: BluetoothDevice, reportId: Int, data: ByteArray) {
        if (hid?.sendReport(device, reportId, data) == true) return
        Log.w(TAG, "HID input report was rejected for ${device.address}")
        _state.value = ConnectionState.Error("Windows is not accepting mouse input. Open Connect and tap Reconnect.")
    }
    @SuppressLint("MissingPermission")
    fun rememberedDevices(): List<BluetoothDevice> = adapter?.bondedDevices?.toList().orEmpty()
    /** Requests a HID connection to an already-paired Windows host. Actual state comes from the callback. */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        val service = hid
        if (!registered || service == null) {
            _state.value = ConnectionState.Error("Enable DeskPilot Bluetooth before connecting")
            return false
        }
        if (service.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED) {
            host = device
            _state.value = ConnectionState.Connected(device.name ?: device.address)
            return true
        }
        rememberHost(device)
        _state.value = ConnectionState.Connecting(device.name ?: device.address)
        val accepted = service.connect(device)
        if (!accepted) _state.value = ConnectionState.Error("Windows rejected the HID connection request. Remove the old pairing and pair DeskPilot again.")
        return accepted
    }

    /** Restarts the HID transport without deleting the Windows Bluetooth pairing. */
    @SuppressLint("MissingPermission")
    fun reconnect(): Boolean {
        val device = host ?: lastRememberedHost()
        val service = hid
        if (!registered || service == null || device == null) {
            _state.value = ConnectionState.Error("No remembered computer to reconnect")
            return false
        }
        _state.value = ConnectionState.Connecting(device.name ?: device.address)
        // Do not begin the new virtual cable until Android confirms the old one is
        // closed. Starting both at once races Windows' HID stack.
        val accepted = if (host?.address == device.address) {
            reconnectAfterClose = device
            service.disconnect(device)
        } else service.connect(device)
        if (!accepted) {
            _state.value = ConnectionState.Error("Could not restart the HID connection. Turn Bluetooth off and on once on Windows, then tap Reconnect.")
        }
        return accepted
    }

    @SuppressLint("MissingPermission")
    private fun lastRememberedHost(): BluetoothDevice? {
        val address = connectionPrefs.getString(LAST_HOST_ADDRESS, null) ?: return null
        return adapter?.bondedDevices?.firstOrNull { it.address == address }
    }

    @SuppressLint("MissingPermission")
    private fun rememberHost(device: BluetoothDevice) {
        connectionPrefs.edit().putString(LAST_HOST_ADDRESS, device.address).apply()
    }
    @SuppressLint("MissingPermission")
    fun stop() {
        clearQueuedMotion()
        releaseAll()
        hid?.unregisterApp()
        hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hid = null
    }

    private companion object {
        const val LAST_HOST_ADDRESS = "last_host_address"
        const val TAG = "DeskPilotHid"
        const val BOOT_REPORT_ID = 0
        const val MIN_REPORT_INTERVAL_MS = 8L
        const val PROFILE_CONNECTION_TIMEOUT_MS = 10_000L
    }

    private fun takeAxis(value: () -> Int): Int = value().coerceIn(-127, 127)
    private fun clearQueuedMotion() {
        reportHandler.removeCallbacksAndMessages(null)
        flushScheduled = false
        queuedDx = 0
        queuedDy = 0
    }
}
