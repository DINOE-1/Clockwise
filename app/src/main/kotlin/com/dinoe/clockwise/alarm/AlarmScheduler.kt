package com.dinoe.clockwise.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.MainActivity
import com.dinoe.clockwise.R
import com.dinoe.clockwise.data.Alarm
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object AlarmScheduler {
    const val EXTRA_ALARM_ID = "com.dinoe.clockwise.extra.ALARM_ID"
    const val EXTRA_SNOOZE = "com.dinoe.clockwise.extra.SNOOZE"
    const val EXTRA_ALARM_NAME = "com.dinoe.clockwise.extra.ALARM_NAME"
    const val EXTRA_ALARM_NOTE = "com.dinoe.clockwise.extra.ALARM_NOTE"
    const val EXTRA_ALARM_TIME = "com.dinoe.clockwise.extra.ALARM_TIME"
    const val EXTRA_TEST = "com.dinoe.clockwise.extra.TEST"
    const val TEST_ALARM_ID = Int.MAX_VALUE.toLong()

    private const val ACTION_SNOOZE = "com.dinoe.clockwise.action.SNOOZE_FIRE"
    private const val ACTION_TEST = "com.dinoe.clockwise.action.TEST_ALARM"
    private const val TEST_REQUEST_CODE = 0x7E57
    private const val SNOOZE_REQUEST_FLAG = 0x40000000

    // Fallback window when the app is not allowed to schedule exact alarms.
    private const val INEXACT_WINDOW_MILLIS = 10 * 60 * 1000L

    fun schedule(context: Context, alarm: Alarm): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = pendingIntent(context, alarm)
        alarmManager.cancel(pendingIntent)
        alarmManager.cancel(
            snoozePendingIntent(context, alarm.id, alarm.name, alarm.note, alarm.time),
        )
        if (!alarm.enabled) return false

        val triggerAtMillis = nextOccurrenceMillis(alarm) ?: return false
        return scheduleExactOrFallback(context, alarmManager, triggerAtMillis, pendingIntent)
    }

    fun scheduleSnooze(
        context: Context,
        alarmId: Long,
        name: String,
        note: String,
        time: Int,
        minutes: Int = 10,
    ): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = snoozePendingIntent(context, alarmId, name, note, time)
        alarmManager.cancel(pendingIntent)
        val triggerAtMillis = System.currentTimeMillis() + minutes * 60_000L
        return scheduleExactOrFallback(context, alarmManager, triggerAtMillis, pendingIntent)
    }

    fun scheduleTestAlarm(context: Context, seconds: Int = 10): Boolean {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val triggerAtMillis = System.currentTimeMillis() + seconds * 1_000L
        val time = LocalTime.now().plusSeconds(seconds.toLong()).toSecondOfDay()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            TEST_REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_TEST)
                .putExtra(EXTRA_TEST, true)
                .putExtra(EXTRA_ALARM_ID, TEST_ALARM_ID)
                .putExtra(EXTRA_ALARM_NAME, context.getString(R.string.alarm_test_title))
                .putExtra(EXTRA_ALARM_NOTE, context.getString(R.string.alarm_test_body))
                .putExtra(EXTRA_ALARM_TIME, time),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
        return scheduleExactOrFallback(context, alarmManager, triggerAtMillis, pendingIntent)
    }

    fun cancel(context: Context, alarm: Alarm) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(pendingIntent(context, alarm))
        alarmManager.cancel(
            snoozePendingIntent(context, alarm.id, alarm.name, alarm.note, alarm.time),
        )
        context.stopService(Intent(context, AlarmService::class.java))
        AlarmNotifier.cancel(context, alarm.id)
    }

    // Used after a reboot to restore every alarm the user left enabled.
    suspend fun rescheduleAllEnabled(context: Context) {
        val alarmDao = (context.applicationContext as ClockwiseApp).database.alarmDao()
        alarmDao.getEnabled().forEach { alarm ->
            // An alarm tied to a specific date that has already passed can never ring again.
            if (!schedule(context, alarm) && alarm.fireDate != null) {
                alarmDao.update(alarm.copy(enabled = false))
            }
        }
    }

    private fun scheduleExactOrFallback(
        context: Context,
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                INEXACT_WINDOW_MILLIS,
                pendingIntent,
            )
            return true
        }

        return try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(context)),
                pendingIntent,
            )
            true
        } catch (_: SecurityException) {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                INEXACT_WINDOW_MILLIS,
                pendingIntent,
            )
            true
        }
    }

    private fun showIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    // Plain alarms ring at the next occurrence of timeSeconds (today, or tomorrow when it has
    // already passed). Alarms tied to a date ring once on that date, or never when it is past.
    private fun nextOccurrenceMillis(
        alarm: Alarm,
        now: LocalDateTime = LocalDateTime.now(),
    ): Long? {
        val next = alarm.nextFireDateTime(now)
        if (!next.isAfter(now)) return null
        return next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    private fun pendingIntent(context: Context, alarm: Alarm): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            alarm.id.toInt(),
            Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_ALARM_ID, alarm.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun snoozePendingIntent(
        context: Context,
        alarmId: Long,
        name: String,
        note: String,
        time: Int,
    ): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            alarmId.toInt() xor SNOOZE_REQUEST_FLAG,
            Intent(context, AlarmReceiver::class.java)
                .setAction(ACTION_SNOOZE)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_SNOOZE, true)
                .putExtra(EXTRA_ALARM_NAME, name)
                .putExtra(EXTRA_ALARM_NOTE, note)
                .putExtra(EXTRA_ALARM_TIME, time),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
