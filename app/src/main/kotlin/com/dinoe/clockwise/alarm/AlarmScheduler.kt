package com.dinoe.clockwise.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.MainActivity
import com.dinoe.clockwise.R
import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.data.MAX_ACTIVE_ALARMS
import java.time.LocalTime

sealed interface ScheduleResult {
    data class Scheduled(val exact: Boolean) : ScheduleResult

    data object Disabled : ScheduleResult

    data object ExactPermissionDenied : ScheduleResult

    data object LimitReached : ScheduleResult

    data object InvalidTime : ScheduleResult

    data class Failed(val message: String? = null) : ScheduleResult
}

data class RestoreResult(
    val scheduled: Int = 0,
    val disabled: Int = 0,
    val failed: Int = 0,
)

object AlarmScheduler {
    const val EXTRA_ALARM_ID = "com.dinoe.clockwise.extra.ALARM_ID"
    const val EXTRA_SNOOZE = "com.dinoe.clockwise.extra.SNOOZE"
    const val EXTRA_ONE_SHOT = "com.dinoe.clockwise.extra.ONE_SHOT"
    const val EXTRA_ALARM_NAME = "com.dinoe.clockwise.extra.ALARM_NAME"
    const val EXTRA_ALARM_NOTE = "com.dinoe.clockwise.extra.ALARM_NOTE"
    const val EXTRA_ALARM_TIME = "com.dinoe.clockwise.extra.ALARM_TIME"
    const val EXTRA_TEST = "com.dinoe.clockwise.extra.TEST"
    const val TEST_ALARM_ID = Int.MAX_VALUE.toLong()

    private const val TAG = "AlarmScheduler"
    private const val ACTION_SNOOZE = "com.dinoe.clockwise.action.SNOOZE_FIRE"
    private const val ACTION_TEST = "com.dinoe.clockwise.action.TEST_ALARM"
    private const val TEST_REQUEST_CODE = 0x7E57
    private const val SNOOZE_REQUEST_FLAG = 0x40000000

    // Fallback window when the app is not allowed to schedule exact alarms.
    private const val INEXACT_WINDOW_MILLIS = 10 * 60 * 1000L

    /**
     * Schedules the effective next trigger for [alarm]. A live snooze always wins over the normal
     * next occurrence. This method deliberately does not cancel a live snooze when scheduling the
     * normal occurrence; that was the cause of snoozes disappearing whenever the process started.
     */
    fun schedule(
        context: Context,
        alarm: Alarm,
        nowMillis: Long = System.currentTimeMillis(),
    ): ScheduleResult {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val normalIntent = pendingIntent(context, alarm)
        val snoozeIntent = snoozePendingIntent(context, alarm.id, alarm.name, alarm.note, alarm.time)

        if (!alarm.enabled) {
            alarmManager.cancel(normalIntent)
            alarmManager.cancel(snoozeIntent)
            return ScheduleResult.Disabled
        }

        val snoozeUntil = alarm.snoozedUntil
        if (snoozeUntil != null && snoozeUntil > nowMillis) {
            // Only one of the two alarms may exist at a time.
            alarmManager.cancel(normalIntent)
            return scheduleExactOrFallback(
                context = context,
                alarmManager = alarmManager,
                triggerAtMillis = snoozeUntil,
                pendingIntent = snoozeIntent,
                allowInexactFallback = true,
            )
        }

        alarmManager.cancel(snoozeIntent)
        val triggerAtMillis = alarm.effectiveTriggerMillis(nowMillis)
            ?: return ScheduleResult.InvalidTime
        return scheduleExactOrFallback(
            context = context,
            alarmManager = alarmManager,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = normalIntent,
            allowInexactFallback = true,
        )
    }

    /** Schedules a one-off snooze for an alarm that is currently ringing. */
    fun scheduleSnooze(
        context: Context,
        alarm: Alarm,
        minutes: Int = 10,
        nowMillis: Long = System.currentTimeMillis(),
    ): ScheduleResult {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val pendingIntent = snoozePendingIntent(
            context,
            alarm.id,
            alarm.name,
            alarm.note,
            alarm.time,
        )
        alarmManager.cancel(pendingIntent)
        alarmManager.cancel(pendingIntent(context, alarm))
        val triggerAtMillis = nowMillis + minutes * 60_000L
        return scheduleExactOrFallback(
            context = context,
            alarmManager = alarmManager,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = pendingIntent,
            allowInexactFallback = true,
        )
    }

    /**
     * A test alarm is only useful when it is exact. Returning a ten-minute inexact window while
     * claiming success was misleading, so callers must show the permission problem instead.
     */
    fun scheduleTestAlarm(context: Context, seconds: Int = 10): ScheduleResult {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            return ScheduleResult.ExactPermissionDenied
        }
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
        return scheduleExactOrFallback(
            context = context,
            alarmManager = alarmManager,
            triggerAtMillis = triggerAtMillis,
            pendingIntent = pendingIntent,
            allowInexactFallback = false,
        )
    }

    /**
     * Cancels the PendingIntents and notification for this alarm. The service is stopped only when
     * it is currently ringing this exact alarm; cancelling a different alarm must never silence
     * the one the user is hearing.
     */
    fun cancel(context: Context, alarm: Alarm) {
        cancelPending(context, alarm)
        AlarmNotifier.cancel(context, alarm.id)
        if (alarm.id != TEST_ALARM_ID &&
            AlarmService.activeAlarmId.value == alarm.id
        ) {
            context.stopService(Intent(context, AlarmService::class.java))
        }
    }

    /** Cancels only the system alarms, leaving a currently ringing service/notification alone. */
    fun cancelPending(context: Context, alarm: Alarm) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        alarmManager.cancel(pendingIntent(context, alarm))
        alarmManager.cancel(
            snoozePendingIntent(context, alarm.id, alarm.name, alarm.note, alarm.time),
        )
    }

    /**
     * Restores all enabled alarms after boot, a package update, or a clock/time-zone change.
     * A future snooze is preserved and a one-shot alarm in the past is disabled instead of being
     * scheduled at an invalid instant.
     */
    suspend fun rescheduleAllEnabled(context: Context): RestoreResult {
        val alarmDao = (context.applicationContext as ClockwiseApp).database.alarmDao()
        val enabled = alarmDao.getEnabled().take(MAX_ACTIVE_ALARMS)
        var scheduled = 0
        var disabled = 0
        var failed = 0
        val now = System.currentTimeMillis()
        enabled.forEach { alarm ->
            val result = schedule(context, alarm, now)
            when (result) {
                is ScheduleResult.Scheduled -> scheduled++
                ScheduleResult.InvalidTime -> {
                    if (alarm.fireDate != null && alarm.snoozedUntil == null) {
                        AlarmNotifier.showMissed(context, alarm)
                    }
                    alarmDao.update(alarm.copy(enabled = false, snoozedUntil = null))
                    disabled++
                }

                ScheduleResult.Disabled -> Unit
                else -> {
                    failed++
                    Log.w(TAG, "Unable to restore alarm ${alarm.id}: $result")
                }
            }
        }
        return RestoreResult(scheduled = scheduled, disabled = disabled, failed = failed)
    }

    private fun scheduleExactOrFallback(
        context: Context,
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
        allowInexactFallback: Boolean,
    ): ScheduleResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            return if (allowInexactFallback) {
                setInexact(alarmManager, triggerAtMillis, pendingIntent)
            } else {
                ScheduleResult.ExactPermissionDenied
            }
        }

        return try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent(context)),
                pendingIntent,
            )
            ScheduleResult.Scheduled(exact = true)
        } catch (e: SecurityException) {
            if (allowInexactFallback) {
                setInexact(alarmManager, triggerAtMillis, pendingIntent)
            } else {
                ScheduleResult.ExactPermissionDenied
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Exact alarm scheduling failed", e)
            ScheduleResult.Failed(e.message)
        }
    }

    private fun setInexact(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
    ): ScheduleResult = try {
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            INEXACT_WINDOW_MILLIS,
            pendingIntent,
        )
        ScheduleResult.Scheduled(exact = false)
    } catch (e: RuntimeException) {
        Log.e(TAG, "Inexact alarm scheduling failed", e)
        ScheduleResult.Failed(e.message)
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
