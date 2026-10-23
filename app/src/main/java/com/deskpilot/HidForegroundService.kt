package com.deskpilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Keeps DeskPilot foreground while its Bluetooth HID registration is active. */
class HidForegroundService : Service() {
    // Holding this reference makes the HID callback live for the whole
    // foreground-service lifetime, not merely while MainActivity is visible.
    private val hidController by lazy { (application as DeskPilotApplication).hidDeviceManager }

    override fun onCreate() {
        super.onCreate()
        hidController // initialise the service-owned controller before advertising readiness
        val channel = NotificationChannel(CHANNEL_ID, "DeskPilot connection", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DeskPilot is ready")
            .setContentText("Bluetooth mouse connection is available")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIFICATION_ID, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val CHANNEL_ID = "deskpilot_hid"; private const val NOTIFICATION_ID = 41
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, HidForegroundService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, HidForegroundService::class.java))
    }
}
