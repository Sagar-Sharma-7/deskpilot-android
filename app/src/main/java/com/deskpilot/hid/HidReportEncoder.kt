package com.deskpilot.hid

/** Mouse packet encoders for the stable basic and optional advanced profiles. */
object HidReportEncoder {
    data class KeyboardUsage(val modifiers: Int, val key: Int)
    const val BASIC_MOUSE_REPORT_ID = 0
    const val ADVANCED_MOUSE_REPORT_ID = 1
    const val KEYBOARD_REPORT_ID = 2
    const val CONSUMER_REPORT_ID = 3
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

    /** US-layout HID usage for the basic characters produced by a phone IME. */
    fun keyboardUsage(character: Char): KeyboardUsage? {
        fun shifted(key: Int) = KeyboardUsage(HID_LEFT_SHIFT, key)
        return when (character) {
            in 'a'..'z' -> KeyboardUsage(0, 0x04 + (character - 'a'))
            in 'A'..'Z' -> shifted(0x04 + (character.lowercaseChar() - 'a'))
            in '1'..'9' -> KeyboardUsage(0, 0x1e + (character - '1'))
            '0' -> KeyboardUsage(0, 0x27)
            ' ' -> KeyboardUsage(0, 0x2c)
            '\n', '\r' -> KeyboardUsage(0, HID_ENTER)
            '\t' -> KeyboardUsage(0, HID_TAB)
            '-' -> KeyboardUsage(0, 0x2d); '_' -> shifted(0x2d)
            '=' -> KeyboardUsage(0, 0x2e); '+' -> shifted(0x2e)
            '[' -> KeyboardUsage(0, 0x2f); '{' -> shifted(0x2f)
            ']' -> KeyboardUsage(0, 0x30); '}' -> shifted(0x30)
            '\\' -> KeyboardUsage(0, 0x31); '|' -> shifted(0x31)
            ';' -> KeyboardUsage(0, 0x33); ':' -> shifted(0x33)
            '\'' -> KeyboardUsage(0, 0x34); '"' -> shifted(0x34)
            '`' -> KeyboardUsage(0, 0x35); '~' -> shifted(0x35)
            ',' -> KeyboardUsage(0, 0x36); '<' -> shifted(0x36)
            '.' -> KeyboardUsage(0, 0x37); '>' -> shifted(0x37)
            '/' -> KeyboardUsage(0, 0x38); '?' -> shifted(0x38)
            '!' -> shifted(0x1e); '@' -> shifted(0x1f); '#' -> shifted(0x20); '$' -> shifted(0x21)
            '%' -> shifted(0x22); '^' -> shifted(0x23); '&' -> shifted(0x24); '*' -> shifted(0x25)
            '(' -> shifted(0x26); ')' -> shifted(0x27)
            else -> null
        }
    }

    fun consumer(controls: Int): ByteArray = byteArrayOf((controls and 0x3f).toByte())

    const val CONSUMER_MUTE = 1 shl 0
    const val CONSUMER_VOLUME_UP = 1 shl 1
    const val CONSUMER_VOLUME_DOWN = 1 shl 2
    const val CONSUMER_NEXT = 1 shl 3
    const val CONSUMER_PREVIOUS = 1 shl 4
    const val CONSUMER_PLAY_PAUSE = 1 shl 5
    const val HID_LEFT_SHIFT = 0x02
    const val HID_BACKSPACE = 0x2a
    const val HID_TAB = 0x2b
    const val HID_ENTER = 0x28

    private fun clamp(value: Int) = value.coerceIn(-127, 127)
}
