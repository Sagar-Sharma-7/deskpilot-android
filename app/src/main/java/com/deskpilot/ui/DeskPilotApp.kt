package com.deskpilot.ui

import android.content.Intent
import android.provider.Settings
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deskpilot.DeskPilotViewModel
import com.deskpilot.actions.DeskAction
import com.deskpilot.data.DeskSettings
import com.deskpilot.hid.ConnectionState
import com.deskpilot.hid.HidReportEncoder
import com.deskpilot.input.TouchPoint

private enum class Screen { TRACKPAD, SETTINGS, CONNECT }

@Composable
fun DeskPilotApp(model: DeskPilotViewModel, settings: DeskSettings, onRequestBluetooth: () -> Unit, onRequestDiscoverable: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.TRACKPAD) }
    MaterialTheme(colorScheme = if (settings.darkTheme) darkColorScheme() else lightColorScheme()) {
        Surface(Modifier.fillMaxSize()) {
            when (screen) {
                Screen.TRACKPAD -> TrackpadScreen(model, settings, { screen = Screen.SETTINGS }, { screen = Screen.CONNECT })
                Screen.SETTINGS -> SettingsScreen(model, settings, { screen = Screen.TRACKPAD })
                Screen.CONNECT -> ConnectScreen(model, { screen = Screen.TRACKPAD }, onRequestBluetooth, onRequestDiscoverable)
            }
        }
    }
}

@Composable private fun TrackpadScreen(model: DeskPilotViewModel, settings: DeskSettings, openSettings: () -> Unit, openConnect: () -> Unit) {
    val state by model.connection.collectAsState()
    val view = LocalView.current
    LaunchedEffect(model, view) {
        model.hapticEvents.collect { cue ->
            val effect = when (cue) {
                DeskPilotViewModel.HapticCue.CLICK -> android.view.HapticFeedbackConstants.VIRTUAL_KEY
                DeskPilotViewModel.HapticCue.DOUBLE_CLICK -> android.view.HapticFeedbackConstants.CONFIRM
                DeskPilotViewModel.HapticCue.DRAG_START -> android.view.HapticFeedbackConstants.LONG_PRESS
                DeskPilotViewModel.HapticCue.SHORTCUT -> android.view.HapticFeedbackConstants.KEYBOARD_TAP
            }
            view.performHapticFeedback(effect)
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("DeskPilot", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                val caption = when (state) { is ConnectionState.Connected -> (state as ConnectionState.Connected).name; else -> "Not connected" }
                Text(caption, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            StatusDot(state, Modifier.clickable(onClick = openConnect))
            TextButton(onClick = openSettings) { Text("Settings") }
        }
        Spacer(Modifier.height(12.dp))
        Touchpad(model, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        QuickActions(model)
        Spacer(Modifier.height(8.dp))
        MouseButtons(model, settings.leftHanded)
    }
}

@Composable private fun StatusDot(state: ConnectionState, modifier: Modifier = Modifier) {
    val color = when (state) { is ConnectionState.Connected -> Color(0xFF22A06B); is ConnectionState.Error, ConnectionState.PermissionRequired, ConnectionState.Unsupported -> MaterialTheme.colorScheme.error; else -> Color(0xFFE2A817) }
    Surface(modifier = modifier.padding(8.dp), shape = RoundedCornerShape(50), color = color.copy(alpha = .15f)) { Text(if (state is ConnectionState.Connected) "Connected" else "Connect", color = color, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 12.sp) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable private fun Touchpad(model: DeskPilotViewModel, modifier: Modifier) {
    Surface(modifier = modifier.pointerInteropFilter { event ->
        fun point(index: Int) = TouchPoint(event.getPointerId(index), event.getX(index), event.getY(index))
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> model.gestures.down(point(event.actionIndex), event.eventTime)
            MotionEvent.ACTION_MOVE -> model.gestures.move(List(event.pointerCount, ::point), event.eventTime)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> model.gestures.up(event.getPointerId(event.actionIndex), event.eventTime)
            MotionEvent.ACTION_CANCEL -> model.gestures.cancel()
        }
        true
    }, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(24.dp), tonalElevation = 1.dp) {}
}

@Composable private fun QuickActions(model: DeskPilotViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val defaults = listOf(DeskAction.COPY, DeskAction.PASTE, DeskAction.UNDO, DeskAction.REDO)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        defaults.forEach { action -> AssistChip(onClick = { model.execute(action) }, label = { Text(action.label) }) }
        Box {
            AssistChip(onClick = { expanded = true }, label = { Text("More") })
            DropdownMenu(expanded, { expanded = false }) {
                listOf(DeskAction.SELECT_ALL, DeskAction.FIND, DeskAction.SAVE, DeskAction.REFRESH, DeskAction.SCREENSHOT, DeskAction.TASK_VIEW, DeskAction.SHOW_DESKTOP, DeskAction.PRECISION, DeskAction.DRAG_LOCK).forEach { action ->
                    DropdownMenuItem(text = { Text(action.label) }, onClick = { model.execute(action); expanded = false })
                }
            }
        }
    }
}

@Composable private fun MouseButtons(model: DeskPilotViewModel, leftHanded: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (leftHanded) {
            HoldButton("Right click", Modifier.weight(1f), { model.setMouseButton(HidReportEncoder.RIGHT, true) }, { model.setMouseButton(HidReportEncoder.RIGHT, false) })
            HoldButton("Left click", Modifier.weight(1f), { model.setMouseButton(HidReportEncoder.LEFT, true) }, { model.setMouseButton(HidReportEncoder.LEFT, false) })
        } else {
            HoldButton("Left click", Modifier.weight(1f), { model.setMouseButton(HidReportEncoder.LEFT, true) }, { model.setMouseButton(HidReportEncoder.LEFT, false) })
            HoldButton("Right click", Modifier.weight(1f), { model.setMouseButton(HidReportEncoder.RIGHT, true) }, { model.setMouseButton(HidReportEncoder.RIGHT, false) })
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable private fun HoldButton(label: String, modifier: Modifier, down: () -> Unit, up: () -> Unit) {
    Surface(modifier = modifier.height(56.dp).pointerInteropFilter { event ->
        when (event.actionMasked) { MotionEvent.ACTION_DOWN -> down(); MotionEvent.ACTION_UP -> up(); MotionEvent.ACTION_CANCEL -> up() }
        true
    }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) { Text(label) }
    }
}

@Composable private fun ConnectScreen(model: DeskPilotViewModel, back: () -> Unit, request: () -> Unit, makeDiscoverable: () -> Unit) {
    val state by model.connection.collectAsState()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
        TextButton(onClick = back) { Text("← Back") }
        Text("Connection", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp)); Text(when (state) { is ConnectionState.Connected -> "Connected to ${(state as ConnectionState.Connected).name}"; is ConnectionState.Connecting -> "Connecting to ${(state as ConnectionState.Connecting).name}…"; is ConnectionState.Error -> (state as ConnectionState.Error).message; ConnectionState.PermissionRequired -> "Nearby devices permission is needed"; ConnectionState.Unsupported -> "This Android version does not support Bluetooth HID device mode"; ConnectionState.Registering -> "Registering DeskPilot as a Bluetooth mouse…"; else -> "Ready to pair" })
        val bluetoothReady = state is ConnectionState.Ready || state is ConnectionState.Connecting || state is ConnectionState.Connected
        Spacer(Modifier.height(20.dp)); Button(onClick = request, enabled = !bluetoothReady) {
            Text(if (bluetoothReady) "DeskPilot Bluetooth is ready" else "Enable DeskPilot Bluetooth")
        }
        Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = makeDiscoverable, enabled = bluetoothReady) { Text("Make phone discoverable for 5 minutes") }
        Spacer(Modifier.height(16.dp)); OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Open Bluetooth settings") }
        Spacer(Modifier.height(24.dp)); Text("First-time pairing", fontWeight = FontWeight.SemiBold)
        Text("1. Wait for Ready to pair, then tap Make phone discoverable and approve Android's prompt.\n2. Within five minutes, on Windows 11: Settings → Bluetooth & devices → Add device → Bluetooth.\n3. Select this phone by its normal Bluetooth name (not necessarily DeskPilot), approve the pairing code on both devices, then return here and tap Connect beside the Galaxy Book.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        val devices = model.rememberedDevices()
        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(20.dp)); Text("Remembered devices", fontWeight = FontWeight.SemiBold)
            devices.forEach { device ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(device.name ?: device.address, modifier = Modifier.weight(1f))
                    TextButton(onClick = { model.connect(device) }, enabled = state is ConnectionState.Ready) { Text("Connect") }
                }
            }
        }
    }
}

@Composable private fun SettingsScreen(model: DeskPilotViewModel, settings: DeskSettings, back: () -> Unit) {
    var chooseFor by remember { mutableStateOf<Boolean?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 24.dp)) {
        TextButton(onClick = back) { Text("← Trackpad") }; Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        SettingsHeader("Pointer")
        Text("Pointer speed ${(settings.pointerSpeed * 100).toInt()}%")
        Slider(settings.pointerSpeed, { model.setPointerSpeed(it) }, valueRange = .35f..2.4f)
        SettingsHeader("Scrolling")
        SettingToggle("Natural scrolling", "Move content with your fingers", settings.naturalScroll, model::setNaturalScroll)
        SettingsHeader("Physical buttons")
        SettingToggle("Use volume buttons as shortcuts", "Only while this foreground screen is connected", settings.useVolumeShortcuts, model::setVolumeShortcuts)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Volume up"); TextButton({ chooseFor = true }) { Text(settings.volumeUp.label) } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Volume down"); TextButton({ chooseFor = false }) { Text(settings.volumeDown.label) } }
        SettingsHeader("Appearance")
        SettingToggle("Dark theme", "", settings.darkTheme, model::setDark); SettingToggle("Haptics", "Clicks and shortcuts only", settings.haptics, model::setHaptics)
        Spacer(Modifier.height(10.dp)); Text("Samsung Side button", fontWeight = FontWeight.SemiBold); Text("DeskPilot cannot intercept the power key. You can set Double press → Open app → DeskPilot in Settings → Advanced features → Side button.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
    chooseFor?.let { up -> ActionChooser(if (up) "Volume up" else "Volume down", { chooseFor = null }) { model.setVolumeAction(up, it); chooseFor = null } }
}

@Composable private fun SettingsHeader(text: String) { Spacer(Modifier.height(20.dp)); Text(text, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
@Composable private fun SettingToggle(title: String, subtitle: String, checked: Boolean, change: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title); if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(checked, change) } }
@Composable private fun ActionChooser(title: String, dismiss: () -> Unit, select: (DeskAction) -> Unit) { AlertDialog(onDismissRequest = dismiss, title = { Text("$title action") }, text = { Column { DeskAction.entries.filter { it != DeskAction.DRAG_LOCK && it != DeskAction.PRECISION }.forEach { action -> Text(action.label, modifier = Modifier.fillMaxWidth().clickable { select(action) }.padding(10.dp)) } } }, confirmButton = { TextButton(dismiss) { Text("Cancel") } }) }
