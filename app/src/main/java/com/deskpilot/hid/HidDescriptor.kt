package com.deskpilot.hid

/**
 * A deliberately small boot-compatible mouse descriptor.
 *
 * Windows' HidBth driver rejected the earlier descriptors with Code 10 on the target
 * Galaxy Book.  This is the exact HID boot-mouse baseline: one top-level mouse
 * collection, no report IDs, no feature reports, and a three-byte input packet.
 *
 * Starting here lets every Windows PC use the same, lowest-common-denominator HID
 * protocol.  Optional axes must only be added after this device is accepted.
 */
object HidDescriptor {
    val bytes = byteArrayOf(
        0x05, 0x01,                         // Usage page: Generic Desktop
        0x09, 0x02,                         // Usage: Mouse
        0xA1.toByte(), 0x01,                // Collection: Application
        0x09, 0x01,                         //   Usage: Pointer
        0xA1.toByte(), 0x00,                //   Collection: Physical
        0x05, 0x09,                         //     Usage page: Button
        0x19, 0x01, 0x29, 0x03,             //     Button 1..3
        0x15, 0x00, 0x25, 0x01,             //     Logical range: 0..1
        0x95.toByte(), 0x03, 0x75, 0x01,
        0x81.toByte(), 0x02,                //     Input: buttons
        0x95.toByte(), 0x01, 0x75, 0x05,
        0x81.toByte(), 0x03,                //     Input: five padding bits
        0x05, 0x01,                         //     Usage page: Generic Desktop
        0x09, 0x30, 0x09, 0x31,             //     Usages: X, Y
        0x15, 0x81.toByte(), 0x25, 0x7F,    //     Logical range: -127..127
        0x75, 0x08, 0x95.toByte(), 0x02,
        0x81.toByte(), 0x06,                //     Input: relative axes
        0xC0.toByte(), 0xC0.toByte()
    )
}
