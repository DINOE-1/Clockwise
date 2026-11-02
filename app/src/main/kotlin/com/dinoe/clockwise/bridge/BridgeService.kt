package com.dinoe.clockwise.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import com.dinoe.clockwise.MainActivity
import com.dinoe.clockwise.R
import com.dinoe.clockwise.ui.withAppLocale
import kotlinx.coroutines.runBlocking

/**
 * Foreground service that owns the bridge HTTP server for the lifetime of the process. The
 * ongoing notification shows the port and offers a stop action.
 */
class BridgeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        BridgeController.start(this)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        BridgeController.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val config = runCatching {
            runBlocking { BridgePreferences(this@BridgeService).snapshot() }
        }.getOrNull()
        val port = config?.port ?: BridgePreferences.DEFAULT_PORT
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(getString(R.string.agent_bridge_notification_title))
            .setContentText(getString(R.string.agent_bridge_notification_text, port))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_alarm_notification),
                    getString(R.string.agent_bridge_notification_stop),
                    stopIntent,
                ).build(),
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "bridge_v1"
        const val ACTION_STOP = "com.dinoe.clockwise.action.STOP_BRIDGE"
        private const val NOTIFICATION_ID = 1001

        fun createChannel(context: Context) {
            val localized = context.withAppLocale()
            val channel = NotificationChannel(
                CHANNEL_ID,
                localized.getString(R.string.agent_bridge_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = localized.getString(R.string.agent_bridge_channel_description)
                setSound(null, null)
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
