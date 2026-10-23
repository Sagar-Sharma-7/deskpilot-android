package com.deskpilot

import android.app.Application
import com.deskpilot.hid.HidDeviceManager

/**
 * Process-wide owner of the Bluetooth HID controller.
 *
 * The controller must outlive an Activity: the foreground service keeps this
 * process alive while Windows has the mouse virtual cable open.
 */
class DeskPilotApplication : Application() {
    val hidDeviceManager: HidDeviceManager by lazy { HidDeviceManager(this) }
}
