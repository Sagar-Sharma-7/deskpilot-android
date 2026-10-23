package com.deskpilot.hid

/** HID payloads exclude the report ID; BluetoothHidDevice supplies it separately. */
object HidReportEncoder {
    const val MOUSE_REPORT_ID = 0

    const val LEFT = 1
    const val RIGHT = 1 shl 1
    const val MIDDLE = 1 shl 2
    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(), clamp(dx).toByte(), clamp(dy).toByte(), clamp(wheel).toByte()
    )

    /** USB/Bluetooth HID boot mouse packet: no report ID, no scroll axes. */
    fun bootMouse(buttons: Int, dx: Int, dy: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(), clamp(dx).toByte(), clamp(dy).toByte()
    )

    private fun clamp(value: Int) = value.coerceIn(-127, 127)
}
