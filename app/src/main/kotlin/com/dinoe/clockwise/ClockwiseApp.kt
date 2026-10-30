package com.dinoe.clockwise

import android.app.Application
import com.dinoe.clockwise.ai.AiRepository
import com.dinoe.clockwise.alarm.AlarmNotifier
import com.dinoe.clockwise.alarm.ReminderNotifier
import com.dinoe.clockwise.alarm.ReminderRestorationCoordinator
import com.dinoe.clockwise.alarm.ReminderScheduler
import com.dinoe.clockwise.data.AppDatabase
import com.dinoe.clockwise.data.ScheduleWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClockwiseApp : Application() {
    /**
     * Process-wide scope for work that must outlive the caller that started it, such as a broadcast
     * receiver's `goAsync()` attempt or a reminder restoration shared by several callers.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val scheduleWriter: ScheduleWriter by lazy { ScheduleWriter(this) }
    val aiRepository: AiRepository by lazy { AiRepository(this) }

    /**
     * Single owner of the reminder restoration scan. Boot, package replacement, clock/time-zone
     * changes, and the explicit recovery paths all request restoration through it, so at most one
     * scan runs at a time.
     */
    val reminderRestoration: ReminderRestorationCoordinator by lazy {
        ReminderRestorationCoordinator(applicationScope) {
            ReminderScheduler.restoreAllUpcomingReminders(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        AlarmNotifier.createChannel(this)
        ReminderNotifier.createChannel(this)
        // Do not reschedule alarms here. A process can start because an alarm is firing; doing a
        // restore at that moment races the receiver and can disable the alarm that is about to
        // ring. BootReceiver handles boot, package updates, and clock/time-zone changes instead.
        //
        // Reminders are not restored here either. Reminder alarms belong to AlarmManager and
        // survive while the process is dead, so a cold start only had to repair them after a
        // force-stop. Every process start used to scan the complete event table for that, including
        // process starts caused by an unrelated alarm delivery. Boot, package replacement, clock
        // changes, and the explicit recovery paths now request a restoration through
        // [reminderRestoration] instead, which runs at most one scan at a time.
        applicationScope.launch {
            runCatching { scheduleWriter.repairOrphanedAlarms() }
        }
    }
}
