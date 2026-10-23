package com.deskpilot.hid

/** Strict three-byte boot-mouse payload. */
object HidReportEncoder {
    const val MOUSE_REPORT_ID = 0

    const val LEFT = 1
    const val RIGHT = 1 shl 1
    const val MIDDLE = 1 shl 2
    fun mouse(buttons: Int, dx: Int, dy: Int): ByteArray = byteArrayOf(
        (buttons and 0x07).toByte(), clamp(dx).toByte(), clamp(dy).toByte()
    )

    private fun clamp(value: Int) = value.coerceIn(-127, 127)
}
