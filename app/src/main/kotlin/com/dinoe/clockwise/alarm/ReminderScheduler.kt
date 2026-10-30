package com.dinoe.clockwise.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.data.Event

/**
 * Schedules the notification reminders of calendar events. Reminders are one-shot alarms per
 * event occurrence, so each row of a repeating series is scheduled independently.
 */
object ReminderScheduler {
    const val EXTRA_EVENT_ID = "com.dinoe.clockwise.extra.REMINDER_EVENT_ID"
    const val EXTRA_TRIGGER_MILLIS = "com.dinoe.clockwise.extra.REMINDER_TRIGGER_MILLIS"

    private const val TAG = "ReminderScheduler"
    private const val INEXACT_WINDOW_MILLIS = 5 * 60 * 1000L

    fun schedule(
        context: Context,
        event: Event,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val triggerAtMillis = event.reminderTriggerMillis()
        val pendingIntent = pendingIntent(appContext, event.id, triggerAtMillis ?: -1L)
        if (triggerAtMillis == null || triggerAtMillis <= nowMillis) {
            alarmManager.cancel(pendingIntent)
            return false
        }

        return try {
            scheduleExactOrInexact(alarmManager, triggerAtMillis, pendingIntent)
            true
        } catch (e: SecurityException) {
            try {
                setInexact(alarmManager, triggerAtMillis, pendingIntent)
                true
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to schedule reminder for event ${event.id}", error)
                false
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Unable to schedule reminder for event ${event.id}", e)
            false
        }
    }

    fun cancel(context: Context, eventId: Long) {
        val appContext = context.applicationContext
        existingPendingIntent(appContext, eventId)?.let { pendingIntent ->
            appContext.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
        }
        ReminderNotifier.cancel(appContext, eventId)
    }

    /**
     * Restores every reminder that is still in the future.
     *
     * The scan always runs through the application-scoped [ReminderRestorationCoordinator], so a
     * request that arrives while another restoration is running joins that run instead of repeating
     * the work.
     */
    suspend fun rescheduleAllUpcoming(
        context: Context,
        trigger: ReminderRestorationTrigger = ReminderRestorationTrigger.RECOVERY,
    ) {
        (context.applicationContext as ClockwiseApp).reminderRestoration.restore(trigger).getOrThrow()
    }

    /**
     * The restoration scan itself. Only [ReminderRestorationCoordinator] calls this so that every
     * entry point shares one serialized operation.
     */
    internal suspend fun restoreAllUpcomingReminders(context: Context) {
        val appContext = context.applicationContext
        val events = (appContext as ClockwiseApp).database.eventDao().getAll()
        val now = System.currentTimeMillis()
        events.forEach { event ->
            if (event.reminderMinutes == Event.REMINDER_OFF) return@forEach
            val triggerAtMillis = event.reminderTriggerMillis() ?: return@forEach
            // Leave due or past reminders alone so a restoration cannot cancel a notification that
            // is being delivered at this very moment.
            if (triggerAtMillis > now) schedule(appContext, event, now)
        }
    }

    private fun scheduleExactOrInexact(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !alarmManager.canScheduleExactAlarms()
        ) {
            setInexact(alarmManager, triggerAtMillis, pendingIntent)
            return
        }
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent,
        )
    }

    private fun setInexact(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        pendingIntent: PendingIntent,
    ) {
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            INEXACT_WINDOW_MILLIS,
            pendingIntent,
        )
    }

    private fun pendingIntent(
        context: Context,
        eventId: Long,
        triggerAtMillis: Long,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        eventId.toInt(),
        Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_EVENT_ID, eventId)
            .putExtra(EXTRA_TRIGGER_MILLIS, triggerAtMillis),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun existingPendingIntent(context: Context, eventId: Long): PendingIntent? =
        PendingIntent.getBroadcast(
            context,
            eventId.toInt(),
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
}
