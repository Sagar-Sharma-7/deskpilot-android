package com.deskpilot.actions

import com.deskpilot.hid.HidDeviceManager
import com.deskpilot.hid.HidReportEncoder

enum class DeskAction(val label: String) {
    COPY("Copy"), PASTE("Paste"), CUT("Cut"), UNDO("Undo"), REDO("Redo"), SELECT_ALL("Select all"), FIND("Find"), SAVE("Save"),
    REFRESH("Refresh"), NEW_TAB("New tab"), CLOSE_TAB("Close tab"), REOPEN_TAB("Reopen tab"), NEXT_TAB("Next tab"), PREVIOUS_TAB("Previous tab"),
    BROWSER_BACK("Browser back"), BROWSER_FORWARD("Browser forward"),
    START("Open Start"), SEARCH("Open Search"), TASK_VIEW("Task View"), SHOW_DESKTOP("Show desktop"), SCREENSHOT("Screenshot"),
    SWITCH_APPS("Switch applications"), SWITCH_APPS_BACK("Previous application"), SNAP_LEFT("Snap window left"), SNAP_RIGHT("Snap window right"), MINIMIZE("Minimize current window"),
    DESKTOP_LEFT("Previous desktop"), DESKTOP_RIGHT("Next desktop"), LEFT_CLICK("Left click"), RIGHT_CLICK("Right click"), MIDDLE_CLICK("Middle click"),
    DRAG_LOCK("Drag lock"), PRECISION("Precision mode"), NO_ACTION("No action")
}

/** Single registry keeps toolbar, gestures and physical buttons consistent. USB HID usage IDs are standard. */
class ActionRegistry(private val hid: HidDeviceManager, private val onPrecision: () -> Unit, private val onDragLock: () -> Unit) {
    fun execute(action: DeskAction) = when (action) {
        DeskAction.COPY -> ctrl(0x06); DeskAction.PASTE -> ctrl(0x19); DeskAction.CUT -> ctrl(0x1b); DeskAction.UNDO -> ctrl(0x1d)
        DeskAction.REDO -> ctrl(0x1c); DeskAction.SELECT_ALL -> ctrl(0x04); DeskAction.FIND -> ctrl(0x09); DeskAction.SAVE -> ctrl(0x16)
        DeskAction.REFRESH -> ctrl(0x15); DeskAction.NEW_TAB -> ctrl(0x17); DeskAction.CLOSE_TAB -> ctrl(0x14); DeskAction.REOPEN_TAB -> hid.key(0x03, 0x15) // Ctrl+Shift+T
        DeskAction.NEXT_TAB -> ctrl(0x2b); DeskAction.PREVIOUS_TAB -> ctrl(0x2a)
        DeskAction.BROWSER_BACK -> hid.key(0x04, 0x50); DeskAction.BROWSER_FORWARD -> hid.key(0x04, 0x4f)
        DeskAction.START -> win(0x00); DeskAction.SEARCH -> win(0x16); DeskAction.TASK_VIEW -> win(0x2b); DeskAction.SHOW_DESKTOP -> win(0x07)
        DeskAction.SCREENSHOT -> hid.key(0x0A, 0x16); DeskAction.SWITCH_APPS -> hid.key(0x04, 0x2b); DeskAction.SWITCH_APPS_BACK -> hid.key(0x06, 0x2b); DeskAction.SNAP_LEFT -> win(0x50); DeskAction.SNAP_RIGHT -> win(0x4f)
        DeskAction.MINIMIZE -> win(0x52); DeskAction.DESKTOP_LEFT -> hid.key(0x0C, 0x50); DeskAction.DESKTOP_RIGHT -> hid.key(0x0C, 0x4f)
        DeskAction.LEFT_CLICK -> hid.click(HidReportEncoder.LEFT); DeskAction.RIGHT_CLICK -> hid.click(HidReportEncoder.RIGHT); DeskAction.MIDDLE_CLICK -> hid.click(HidReportEncoder.MIDDLE)
        DeskAction.DRAG_LOCK -> onDragLock(); DeskAction.PRECISION -> onPrecision(); DeskAction.NO_ACTION -> Unit
    }
    private fun ctrl(key: Int) = hid.key(0x01, key)
    private fun win(key: Int) = hid.key(0x08, key)
}
