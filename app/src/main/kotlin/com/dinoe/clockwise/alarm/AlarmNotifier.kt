package com.dinoe.clockwise.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.dinoe.clockwise.MainActivity
import com.dinoe.clockwise.R
import com.dinoe.clockwise.data.Alarm

object AlarmNotifier {
    private const val CHANNEL_ID = "alarm_ringing_v2"
    private const val SNOOZE_REQUEST_FLAG = 0x20000000

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alarm_channel_ringing_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.alarm_channel_ringing_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun buildRingingNotification(
        context: Context,
        alarmId: Long,
        title: String,
        body: String,
        fullScreenIntent: PendingIntent,
        withActions: Boolean = true,
    ): Notification {
        val stopIntent = stopPendingIntent(context, alarmId)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenIntent, true)
            .setContentIntent(fullScreenIntent)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setDeleteIntent(stopIntent)

        if (withActions) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_alarm_notification),
                    context.getString(R.string.alarm_stop),
                    stopIntent,
                ).build(),
            )
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_alarm_notification),
                    context.getString(R.string.alarm_snooze_minutes, 10),
                    snoozePendingIntent(context, alarmId),
                ).build(),
            )
        }

        return builder.build()
    }

    fun showStartFailure(context: Context, alarm: Alarm) {
        val title = alarm.name.ifBlank { context.getString(R.string.alarm_ringing_title) }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.alarm_start_failed))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    alarm.id.toInt(),
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationId(alarm.id), notification)
    }

    fun cancel(context: Context, alarmId: Long) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(alarmId))
    }

    fun notificationId(alarmId: Long): Int = alarmId.toInt()

    private fun stopPendingIntent(context: Context, alarmId: Long): PendingIntent =
        PendingIntent.getService(
            context,
            notificationId(alarmId),
            Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_STOP)
                .putExtra(AlarmService.EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun snoozePendingIntent(context: Context, alarmId: Long): PendingIntent =
        PendingIntent.getService(
            context,
            notificationId(alarmId) xor SNOOZE_REQUEST_FLAG,
            Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_SNOOZE)
                .putExtra(AlarmService.EXTRA_ALARM_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
