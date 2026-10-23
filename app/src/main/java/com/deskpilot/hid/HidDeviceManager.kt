package com.deskpilot.hid

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
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
    private var protocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE
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
                _state.value = ConnectionState.Connected(pluggedDevice.name ?: pluggedDevice.address)
            } else {
                _state.value = ConnectionState.Ready
                reconnectLastHost()
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
                }
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> if (host?.address == device.address || _state.value is ConnectionState.Connecting) {
                    host = null
                    buttons = 0
                    _state.value = if (registered) ConnectionState.Ready else ConnectionState.Error("Bluetooth HID disconnected")
                }
            }
        }

        /**
         * A Windows host may choose HID boot mode after restoring a connection. In
         * that mode it will ignore report-ID packets, so input must use the boot
         * mouse/keyboard packet shape and report ID zero.
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
            val report = when (id.toInt()) {
                HidReportEncoder.MOUSE_REPORT_ID -> HidReportEncoder.mouse(buttons, 0, 0)
                HidReportEncoder.KEYBOARD_REPORT_ID -> HidReportEncoder.keyboard(0)
                0 -> if (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) HidReportEncoder.bootMouse(buttons, 0, 0) else null
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
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
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
            if (!registered) register() else if (host == null) reconnectLastHost()
            return
        }
        _state.value = ConnectionState.Registering
        adapter.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
    }

    @SuppressLint("MissingPermission")
    private fun register() {
        val ok = hid?.registerApp(
            BluetoothHidDeviceAppSdpSettings("DeskPilot", "Wireless trackpad and mouse", "DeskPilot", BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.bytes),
            null, null, executor, callback
        ) == true
        if (!ok) _state.value = ConnectionState.Error("Could not register the Bluetooth HID device")
    }

    fun move(dx: Int, dy: Int) = mouse(dx, dy)
    fun scroll(vertical: Int, horizontal: Int) = mouse(0, 0, vertical, horizontal)
    fun setButton(button: Int, down: Boolean) {
        buttons = if (down) buttons or button else buttons and button.inv()
        mouse(0, 0)
    }
    fun click(button: Int) { setButton(button, true); setButton(button, false) }
    fun releaseAll() {
        buttons = 0
        mouse(0, 0)
        key(0)
    }
    fun key(modifiers: Int, vararg keys: Int) {
        val currentHost = host ?: return
        sendKeyboardReport(currentHost, HidReportEncoder.keyboard(modifiers, *keys))
        sendKeyboardReport(currentHost, HidReportEncoder.keyboard(0))
    }
    private fun mouse(dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0) {
        val currentHost = host ?: return
        if (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) {
            sendInputReport(currentHost, BOOT_REPORT_ID, HidReportEncoder.bootMouse(buttons, dx, dy))
        } else {
            sendInputReport(currentHost, HidReportEncoder.MOUSE_REPORT_ID, HidReportEncoder.mouse(buttons, dx, dy, wheel, pan))
        }
    }

    private fun sendKeyboardReport(device: BluetoothDevice, data: ByteArray) =
        sendInputReport(device, if (protocol == BluetoothHidDevice.PROTOCOL_BOOT_MODE) BOOT_REPORT_ID else HidReportEncoder.KEYBOARD_REPORT_ID, data)

    /** Makes a stale virtual-cable connection visible instead of silently dropping input. */
    private fun sendInputReport(device: BluetoothDevice, reportId: Int, data: ByteArray) {
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
        // Disconnecting an existing virtual cable causes the callback to return to
        // Ready, where reconnectLastHost() starts a clean connection. Starting a
        // second connection before the disconnect callback races Windows' HID stack.
        val accepted = if (host?.address == device.address) service.disconnect(device) else service.connect(device)
        if (!accepted) {
            _state.value = ConnectionState.Error("Could not restart the HID connection. Turn Bluetooth off and on once on Windows, then tap Reconnect.")
        }
        return accepted
    }

    /** Reconnects only to the computer the user previously selected—never an arbitrary paired device. */
    @SuppressLint("MissingPermission")
    private fun reconnectLastHost() {
        if (!registered || host != null) return
        val rememberedHost = lastRememberedHost() ?: return
        _state.value = ConnectionState.Connecting(rememberedHost.name ?: rememberedHost.address)
        if (hid?.connect(rememberedHost) != true) {
            _state.value = ConnectionState.Ready
        }
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
        releaseAll()
        hid?.unregisterApp()
        hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hid = null
    }

    private companion object {
        const val LAST_HOST_ADDRESS = "last_host_address"
        const val TAG = "DeskPilotHid"
        const val BOOT_REPORT_ID = 0
    }
}
