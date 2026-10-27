package com.dinoe.clockwise.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import com.dinoe.clockwise.MainActivity
import com.dinoe.clockwise.R
import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.ui.withAppLocale
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object ReminderNotifier {
    const val CHANNEL_ID = "event_reminders_v1"

    private const val NOTIFICATION_ID = 1
    private const val CONTENT_REQUEST_CODE = 0x6C000000

    fun createChannel(context: Context) {
        val localized = context.withAppLocale()
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = localized.getString(R.string.reminder_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, event: Event) {
        val localized = context.withAppLocale()
        val title = event.title.ifBlank {
            localized.getString(R.string.reminder_notification_title)
        }
        val body = localized.getString(
            R.string.reminder_notification_body,
            formatTime(localized, event.start),
        )
        val bigText = if (event.note.isNotBlank()) "${event.note}\n$body" else body
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setSubText(
                localized.getString(
                    R.string.reminder_notification_advance,
                    event.reminderMinutes,
                ),
            )
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent(context, event.id))
            .setAutoCancel(true)
            .setWhen(event.reminderTriggerMillis() ?: System.currentTimeMillis())
            .setShowWhen(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(notificationTag(event.id), NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context, eventId: Long) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(notificationTag(eventId), NOTIFICATION_ID)
    }

    private fun notificationTag(eventId: Long): String = "event-reminder:$eventId"

    private fun contentIntent(context: Context, eventId: Long): PendingIntent =
        PendingIntent.getActivity(
            context,
            CONTENT_REQUEST_CODE xor eventId.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_EVENT_ID, eventId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun formatTime(context: Context, secondsOfDay: Int): String {
        if (secondsOfDay !in 0..86_399) return context.getString(R.string.alarm_unknown_time)
        val locale = context.resources.configuration.locales[0]
        val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hm"
        return DateTimeFormatter.ofPattern(
            DateFormat.getBestDateTimePattern(locale, skeleton),
            locale,
        ).format(LocalTime.ofSecondOfDay(secondsOfDay.toLong()))
    }
}
