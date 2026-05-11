package com.stalkerhek.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.stalkerhek.tv.engine.EngineController
import com.stalkerhek.tv.util.getLocalIpAddress

class EngineService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted — run without foreground priority
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        EngineController.init(filesDir.absolutePath, this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Engine Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Stalkerhek engine running status"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val ip = getLocalIpAddress()
        val mgmtUrl = "http://$ip:4400"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Stalkerhek")
            .setContentText("Management: $mgmtUrl")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "stalkerhek_engine"
        private const val NOTIFICATION_ID = 1
    }
}
