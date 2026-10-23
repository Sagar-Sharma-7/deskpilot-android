package com.deskpilot.hid

/** HID payloads exclude the report ID; BluetoothHidDevice supplies it separately. */
object HidReportEncoder {
    const val MOUSE_REPORT_ID = 1
    const val KEYBOARD_REPORT_ID = 2

    const val LEFT = 1
    const val RIGHT = 1 shl 1
    const val MIDDLE = 1 shl 2
    const val BACK = 1 shl 3
    const val FORWARD = 1 shl 4

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int = 0, pan: Int = 0): ByteArray = byteArrayOf(
        (buttons and 0x1f).toByte(), clamp(dx).toByte(), clamp(dy).toByte(),
        clamp(wheel).toByte(), clamp(pan).toByte()
    )

    /** USB/Bluetooth HID boot mouse packet: no report ID, no scroll axes. */
    fun bootMouse(buttons: Int, dx: Int, dy: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(), clamp(dx).toByte(), clamp(dy).toByte()
    )

    fun keyboard(modifiers: Int, vararg keys: Int): ByteArray {
        val report = ByteArray(8)
        report[0] = modifiers.toByte()
        keys.take(6).forEachIndexed { index, key -> report[index + 2] = key.toByte() }
        return report
    }

    private fun clamp(value: Int) = value.coerceIn(-127, 127)
}
