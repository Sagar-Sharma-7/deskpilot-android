package com.deskpilot.hid

/** Mouse packet encoders for the stable basic and optional advanced profiles. */
object HidReportEncoder {
    const val BASIC_MOUSE_REPORT_ID = 0
    const val ADVANCED_MOUSE_REPORT_ID = 1
    const val KEYBOARD_REPORT_ID = 2
    const val LEFT = 1
    const val RIGHT = 1 shl 1
    const val MIDDLE = 1 shl 2

    fun mouse(buttons: Int, dx: Int, dy: Int, wheel: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(), clamp(dx).toByte(), clamp(dy).toByte(), clamp(wheel).toByte()
    )

    fun advancedMouse(buttons: Int, dx: Int, dy: Int, wheel: Int, pan: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(), clamp(dx).toByte(), clamp(dy).toByte(), clamp(wheel).toByte(), clamp(pan).toByte()
    )

    fun bootMouse(buttons: Int, dx: Int, dy: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(), clamp(dx).toByte(), clamp(dy).toByte()
    )

    fun keyboard(modifiers: Int, vararg keys: Int): ByteArray = ByteArray(8).also { report ->
        report[0] = modifiers.toByte()
        keys.take(6).forEachIndexed { index, key -> report[index + 2] = key.toByte() }
    }

    private fun clamp(value: Int) = value.coerceIn(-127, 127)
}
