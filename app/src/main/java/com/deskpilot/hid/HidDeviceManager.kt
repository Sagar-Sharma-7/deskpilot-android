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
        val device = hid ?: return
        device.sendReport(currentHost, HidReportEncoder.KEYBOARD_REPORT_ID, HidReportEncoder.keyboard(modifiers, *keys))
        device.sendReport(currentHost, HidReportEncoder.KEYBOARD_REPORT_ID, HidReportEncoder.keyboard(0))
    }
    private fun mouse(dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0) {
        val currentHost = host ?: return
        hid?.sendReport(currentHost, HidReportEncoder.MOUSE_REPORT_ID, HidReportEncoder.mouse(buttons, dx, dy, wheel, pan))
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
        val accepted = service.connect(device)
        if (!accepted) _state.value = ConnectionState.Error("Windows rejected the HID connection request. Remove the old pairing and pair DeskPilot again.")
        return accepted
    }

    /** Reconnects only to the computer the user previously selected—never an arbitrary paired device. */
    @SuppressLint("MissingPermission")
    private fun reconnectLastHost() {
        if (!registered || host != null) return
        val address = connectionPrefs.getString(LAST_HOST_ADDRESS, null) ?: return
        val rememberedHost = adapter?.bondedDevices?.firstOrNull { it.address == address } ?: return
        hid?.connect(rememberedHost)
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

    private companion object { const val LAST_HOST_ADDRESS = "last_host_address" }
}
