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
    private var reconnectAfterRegistration: BluetoothDevice? = null
    private var registrationRefreshScheduled = false
    private var queuedDx = 0
    private var queuedDy = 0
    private var flushScheduled = false
    private var lastReportAtMs = 0L
    private val reportHandler = Handler(Looper.getMainLooper())
    /**
     * Delayed tap releases must not be allowed to release a later hold/drag.
     * Keep each pending release so a new explicit button action can supersede it.
     */
    private val pendingClickReleases = mutableMapOf<Int, Runnable>()
    private val connectionPrefs: SharedPreferences = context.getSharedPreferences("deskpilot_hid_connection", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Starting)
    val state: StateFlow<ConnectionState> = _state
    private val executor: Executor = context.mainExecutor

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@HidDeviceManager.registered = registered
            if (!registered) {
                // A manual input rebuild deliberately unregisters first. Android
                // delivers this callback before the app can be registered again.
                if (reconnectAfterRegistration != null) {
                    scheduleFreshRegistration()
                    return
                }
                _state.value = ConnectionState.Error("DeskPilot is no longer registered as a Bluetooth HID device")
                return
            }
            val deviceToReconnect = reconnectAfterRegistration
            if (deviceToReconnect != null) {
                reconnectAfterRegistration = null
                // Some phones restore the fresh virtual cable before delivering
                // this registration callback. Do not call connect() a second time
                // in that case: Android returns false even though the new HID
                // channel is already usable.
                val restoredDevice = pluggedDevice
                if (restoredDevice != null && restoredDevice.address == deviceToReconnect.address) {
                    host = restoredDevice
                    protocol = BluetoothHidDevice.PROTOCOL_REPORT_MODE
                    rememberHost(restoredDevice)
                    resetInputState(restoredDevice)
                    _state.value = ConnectionState.Connected(restoredDevice.name ?: restoredDevice.address)
                } else {
                    host = null
                    _state.value = ConnectionState.Connecting(deviceToReconnect.name ?: deviceToReconnect.address)
                    connectAfterFreshRegistration(deviceToReconnect)
                }
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
                    // interrupt channel opens. In particular, never carry a
                    // left-button-down state across a virtual-cable reconnect.
                    resetInputState(device)
                }
                BluetoothProfile.STATE_DISCONNECTED, BluetoothProfile.STATE_DISCONNECTING -> if (host?.address == device.address || _state.value is ConnectionState.Connecting) {
                    host = null
                    buttons = 0
                    clearQueuedMotion()
                    val reconnect = reconnectAfterClose
                    reconnectAfterClose = null
                    if (reconnect != null && state == BluetoothProfile.STATE_DISCONNECTED && registered) {
                        _state.value = ConnectionState.Connecting(reconnect.name ?: reconnect.address)
                        val restarted = try {
                            hid?.connect(reconnect) == true
                        } catch (error: RuntimeException) {
                            Log.e(TAG, "Could not reconnect Bluetooth HID", error)
                            false
                        }
                        if (!restarted) _state.value = ConnectionState.Ready
                    } else {
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

        /**
         * Windows can ask for the current input report immediately after restoring a
         * virtual-cable connection.  Without a reply the Bluetooth link looks
         * connected, but Windows may not finish bringing up its HID input channel.
         */
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            Log.i(TAG, "HID GET_REPORT type=$type id=$id size=$bufferSize from ${device.address}")
            val report = when (id.toInt()) {
                HidReportEncoder.MOUSE_REPORT_ID -> HidReportEncoder.mouse(buttons, 0, 0)
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
            hid = proxy as? BluetoothHidDevice
            if (hid == null) {
                _state.value = ConnectionState.Error("This phone returned an invalid Bluetooth HID service.")
                return
            }
            register()
        }
        override fun onServiceDisconnected(profile: Int) {
            hid = null
            host = null
            buttons = 0
            clearQueuedMotion()
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
        val ok = try {
            hid?.registerApp(
                BluetoothHidDeviceAppSdpSettings("DeskPilot", "Wireless trackpad and mouse", "DeskPilot", BluetoothHidDevice.SUBCLASS1_MOUSE, HidDescriptor.bytes),
                null, null, executor, callback
            ) == true
        } catch (error: SecurityException) {
            reportBluetoothFailure("Bluetooth permission was revoked", error)
            return
        } catch (error: RuntimeException) {
            reportBluetoothFailure("This phone could not register Bluetooth HID", error)
            return
        }
        if (!ok) _state.value = ConnectionState.Error("Could not register the Bluetooth HID device")
    }

    fun move(dx: Int, dy: Int) = mouse(dx, dy)
    fun scroll(vertical: Int, horizontal: Int) {
        if (vertical != 0 || horizontal != 0) Log.d(TAG, "Scroll disabled in boot-mouse stability mode")
    }
    fun setButton(button: Int, down: Boolean) {
        // A deliberate touch/drag action always wins over a previous tap timer.
        cancelPendingClickRelease(button)
        buttons = if (down) buttons or button else buttons and button.inv()
        mouse(0, 0, immediate = true)
    }
    /**
     * A back-to-back press/release can be collapsed by Windows' Bluetooth HID
     * path. Keep a tap pressed briefly so it is observed as a real click.
     */
    fun click(button: Int) {
        cancelPendingClickRelease(button)
        buttons = buttons or button
        mouse(0, 0, immediate = true)
        val release = Runnable {
            pendingClickReleases.remove(button)
            buttons = buttons and button.inv()
            mouse(0, 0, immediate = true)
        }
        pendingClickReleases[button] = release
        reportHandler.postDelayed(release, CLICK_HOLD_MS)
    }
    fun releaseAll() {
        // This is the failsafe used when the app loses focus or the user asks to
        // clear a drag. Drop queued movement and every delayed tap before sending
        // the neutral boot-mouse report.
        clearQueuedMotion()
        buttons = 0
        mouse(0, 0, immediate = true)
    }
    fun key(modifiers: Int, vararg keys: Int) {
        Log.d(TAG, "Keyboard shortcut disabled in boot-mouse stability mode")
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
        val accepted = try {
            hid?.sendReport(device, reportId, data) == true
        } catch (error: RuntimeException) {
            reportBluetoothFailure("This phone rejected the HID input report", error)
            return
        }
        if (accepted) return
        Log.w(TAG, "HID input report was rejected for ${device.address}")
        _state.value = ConnectionState.Error("Windows is not accepting mouse input. Open Connect and tap Rebuild mouse input.")
    }

    /** The stable profile flushes the short mouse queue in report order. */
    fun discardPendingMotion() = Unit
    @SuppressLint("MissingPermission")
    fun rememberedDevices(): List<BluetoothDevice> = try {
        adapter?.bondedDevices?.toList().orEmpty()
    } catch (error: SecurityException) {
        reportBluetoothFailure("Nearby devices permission is required", error)
        emptyList()
    }
    /** Requests a HID connection to an already-paired Windows host. Actual state comes from the callback. */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        val service = hid
        if (!registered || service == null) {
            _state.value = ConnectionState.Error("Enable DeskPilot Bluetooth before connecting")
            return false
        }
        val connectionState = try {
            service.getConnectionState(device)
        } catch (error: SecurityException) {
            reportBluetoothFailure("Nearby devices permission is required", error)
            return false
        } catch (error: RuntimeException) {
            reportBluetoothFailure("This phone could not inspect the HID connection", error)
            return false
        }
        if (connectionState == BluetoothProfile.STATE_CONNECTED) {
            // A device can retain a Bluetooth link after Windows has dropped its
            // HID interrupt channel. Re-open the virtual cable instead of
            // presenting that stale connection as a working mouse.
            return restartVirtualCable(service, device)
        }
        rememberHost(device)
        _state.value = ConnectionState.Connecting(device.name ?: device.address)
        val accepted = try {
            service.connect(device)
        } catch (error: SecurityException) {
            reportBluetoothFailure("Nearby devices permission is required", error)
            return false
        } catch (error: RuntimeException) {
            reportBluetoothFailure("This phone could not start the HID connection", error)
            return false
        }
        if (!accepted) _state.value = ConnectionState.Error("Windows rejected the HID connection request. Remove the old pairing and pair DeskPilot again.")
        return accepted
    }

    /**
     * Rebuilds the Android HID registration and reconnects without deleting the
     * Windows Bluetooth pairing. This repairs a connected-but-inactive HID
     * virtual cable, which a simple Bluetooth reconnect does not always fix.
     */
    @SuppressLint("MissingPermission")
    fun reconnect(): Boolean {
        val device = host ?: lastRememberedHost()
        val service = hid
        if (!registered || service == null || device == null) {
            _state.value = ConnectionState.Error("No remembered computer to reconnect")
            return false
        }
        releaseAll()
        reconnectAfterClose = null
        reconnectAfterRegistration = device
        registered = false
        host = null
        _state.value = ConnectionState.Registering
        val accepted = try {
            // Unregistering closes any stale virtual cable. The physical Windows
            // pairing remains intact; on registration Android reconnects it below.
            service.unregisterApp()
        } catch (error: SecurityException) {
            reconnectAfterRegistration = null
            reportBluetoothFailure("Nearby devices permission is required", error)
            return false
        } catch (error: RuntimeException) {
            reconnectAfterRegistration = null
            reportBluetoothFailure("This phone could not restart the HID connection", error)
            return false
        }
        scheduleFreshRegistration()
        if (!accepted) {
            Log.w(TAG, "HID unregister returned false; attempting a clean registration anyway")
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun restartVirtualCable(service: BluetoothHidDevice, device: BluetoothDevice): Boolean {
        releaseAll()
        reconnectAfterClose = device
        _state.value = ConnectionState.Connecting(device.name ?: device.address)
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
        if (!accepted) {
            reconnectAfterClose = null
            _state.value = ConnectionState.Error("Could not refresh the HID connection. Tap Rebuild mouse input.")
        }
        return accepted
    }

    private fun scheduleFreshRegistration() {
        if (registrationRefreshScheduled) return
        registrationRefreshScheduled = true
        reportHandler.postDelayed({
            registrationRefreshScheduled = false
            if (reconnectAfterRegistration != null && hid != null && !registered) register()
        }, FRESH_REGISTRATION_DELAY_MS)
    }

    @SuppressLint("MissingPermission")
    private fun connectAfterFreshRegistration(device: BluetoothDevice) {
        val accepted = try {
            hid?.connect(device) == true
        } catch (error: SecurityException) {
            reportBluetoothFailure("Nearby devices permission is required", error)
            return
        } catch (error: RuntimeException) {
            reportBluetoothFailure("This phone could not restart the HID connection", error)
            return
        }
        if (!accepted) {
            _state.value = ConnectionState.Error("Windows rejected the refreshed HID connection. Turn Bluetooth off and on once on Windows, then tap Rebuild mouse input.")
        }
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
        reconnectAfterClose = null
        reconnectAfterRegistration = null
        clearQueuedMotion()
        releaseAll()
        hid?.unregisterApp()
        hid?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hid = null
    }

    private companion object {
        const val LAST_HOST_ADDRESS = "last_host_address"
        const val TAG = "DeskPilotHid"
        const val MIN_REPORT_INTERVAL_MS = 8L
        const val CLICK_HOLD_MS = 28L
        const val PROFILE_CONNECTION_TIMEOUT_MS = 10_000L
        const val FRESH_REGISTRATION_DELAY_MS = 350L
    }

    private fun takeAxis(value: () -> Int): Int = value().coerceIn(-127, 127)

    /** Resets both Android's local state and the host's state at a cable boundary. */
    private fun resetInputState(device: BluetoothDevice?) {
        clearQueuedMotion()
        buttons = 0
        if (device != null) {
            sendNow(device, HidReportEncoder.MOUSE_REPORT_ID, HidReportEncoder.mouse(0, 0, 0))
        }
    }

    private fun cancelPendingClickRelease(button: Int) {
        pendingClickReleases.remove(button)?.let(reportHandler::removeCallbacks)
    }

    private fun reportBluetoothFailure(message: String, error: Exception) {
        Log.e(TAG, message, error)
        _state.value = ConnectionState.Error("$message. Check Bluetooth permissions and try again.")
    }

    private fun clearQueuedMotion() {
        reportHandler.removeCallbacksAndMessages(null)
        pendingClickReleases.clear()
        flushScheduled = false
        queuedDx = 0
        queuedDy = 0
    }
}
