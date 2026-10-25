package com.dinoe.clockwise.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.format.DateFormat
import com.dinoe.clockwise.MainActivity
import com.dinoe.clockwise.R
import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.ui.withAppLocale
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object AlarmNotifier {
    const val CHANNEL_ID = "alarm_ringing_v2"
    private const val SNOOZE_REQUEST_FLAG = 0x20000000

    fun createChannel(context: Context) {
        val localized = context.withAppLocale()
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.alarm_channel_ringing_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = localized.getString(R.string.alarm_channel_ringing_description)
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
        val localized = context.withAppLocale()
        val title = alarm.name.ifBlank { localized.getString(R.string.alarm_ringing_title) }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(title)
            .setContentText(localized.getString(R.string.alarm_start_failed))
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

    /**
     * Tells the user that a dated alarm expired while the device/app was unavailable. This is
     * shown once, just before the alarm is disabled by the boot/restore reconciliation.
     */
    fun showMissed(context: Context, alarm: Alarm) {
        val localized = context.withAppLocale()
        val time = formatTime(localized, alarm.time)
        val title = alarm.name.ifBlank { localized.getString(R.string.alarm_missed_title) }
        val body = localized.getString(R.string.alarm_missed_body, time)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM)
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

    private fun formatTime(context: Context, secondsOfDay: Int): String {
        if (secondsOfDay !in 0..86_399) return context.getString(R.string.alarm_unknown_time)
        val locale = context.resources.configuration.locales[0]
        val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
        return DateTimeFormatter.ofPattern(
            DateFormat.getBestDateTimePattern(locale, skeleton),
            locale,
        ).format(LocalTime.ofSecondOfDay(secondsOfDay.toLong()))
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
