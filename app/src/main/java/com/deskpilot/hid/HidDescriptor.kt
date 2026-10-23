package com.deskpilot.hid

/** Fixed single-collection mouse: three buttons, X/Y, and a vertical wheel. */
object HidDescriptor {
    val bytes = byteArrayOf(
        0x05, 0x01,                         // Generic Desktop
        0x09, 0x02,                         // Mouse
        0xA1.toByte(), 0x01,                // Application collection
        0x09, 0x01,                         // Pointer
        0xA1.toByte(), 0x00,                // Physical collection
        0x05, 0x09,                         // Button page
        0x19, 0x01, 0x29, 0x03,             // Buttons 1..3
        0x15, 0x00, 0x25, 0x01,
        0x95.toByte(), 0x03, 0x75, 0x01,
        0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05,
        0x81.toByte(), 0x03,                // Padding
        0x05, 0x01,                         // Generic Desktop
        0x09, 0x30, 0x09, 0x31, 0x09, 0x38, // X, Y, Wheel
        0x15, 0x81.toByte(), 0x25, 0x7F,
        0x75, 0x08, 0x95.toByte(), 0x03,
        0x81.toByte(), 0x06,                // Relative input
        0xC0.toByte(), 0xC0.toByte()
    )
}
