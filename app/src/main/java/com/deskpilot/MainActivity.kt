package com.deskpilot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deskpilot.ui.DeskPilotApp

class MainActivity : ComponentActivity() {
    private val model: DeskPilotViewModel by viewModels()
    private val bluetoothPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.startBluetooth(hasBluetoothPermissions()) }
    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { /* Android returns the approved duration; the UI remains ready for pairing. */ }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent {
            val settings by model.settings.collectAsStateWithLifecycle()
            DeskPilotApp(model, settings, onRequestBluetooth = {
                if (hasBluetoothPermissions()) model.startBluetooth(true) else bluetoothPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
            }, onRequestDiscoverable = {
                discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
            })
        }
    }
    override fun onStart() { super.onStart(); model.startBluetooth(hasBluetoothPermissions()) }
    override fun onPause() { model.releaseInputs(); super.onPause() }
    override fun onStop() { model.releaseInputs(); super.onStop() }
    override fun onDestroy() { if (isFinishing) model.close(); super.onDestroy() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (model.handleVolumeKey(event)) return true
        return super.dispatchKeyEvent(event)
    }
    private fun hasBluetoothPermissions() = android.os.Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
}
