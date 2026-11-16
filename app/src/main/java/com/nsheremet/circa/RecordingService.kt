package com.nsheremet.circa

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Ring lab: an ongoing "Recording…" notification while a motion or light recording runs. The recording itself stays in
 * the Ring lab screen's coroutine; this only keeps the process in the foreground (not frozen once the screen locks) and the CPU awake.
 */
class RecordingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ring_sync", "Circa sync", NotificationManager.IMPORTANCE_LOW)) // SyncWorker's channel
        val notif = NotificationCompat.Builder(this, "ring_sync")
            .setSmallIcon(R.drawable.ic_stat_circa)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Recording…")
            .setOngoing(true)
            .build()
        startForeground(6, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // timeout only guards a leak; the screen stops this service on every exit path
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "circa:recording").apply { acquire(3 * 3_600_000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
