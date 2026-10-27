package com.dinoe.clockwise

import android.app.Application
import com.dinoe.clockwise.ai.AiRepository
import com.dinoe.clockwise.alarm.AlarmNotifier
import com.dinoe.clockwise.alarm.ReminderNotifier
import com.dinoe.clockwise.alarm.ReminderScheduler
import com.dinoe.clockwise.data.AppDatabase
import com.dinoe.clockwise.data.ScheduleWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ClockwiseApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.build(this) }
    val scheduleWriter: ScheduleWriter by lazy { ScheduleWriter(this) }
    val aiRepository: AiRepository by lazy { AiRepository(this) }

    override fun onCreate() {
        super.onCreate()
        AlarmNotifier.createChannel(this)
        ReminderNotifier.createChannel(this)
        // Do not reschedule alarms here. A process can start because an alarm is firing; doing a
        // restore at that moment races the receiver and can disable the alarm that is about to
        // ring. BootReceiver handles boot, package updates, and clock/time-zone changes instead.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { scheduleWriter.repairOrphanedAlarms() }
            // Reminders only post a notification, so restoring them on process start cannot race
            // a ringing alarm. This recovers them after a force-stop or an OEM killing the app.
            runCatching { ReminderScheduler.rescheduleAllUpcoming(this@ClockwiseApp) }
        }
    }
}
