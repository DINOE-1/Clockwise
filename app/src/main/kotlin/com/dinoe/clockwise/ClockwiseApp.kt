package com.dinoe.clockwise

import android.app.Application
import com.dinoe.clockwise.ai.AiRepository
import com.dinoe.clockwise.alarm.AlarmNotifier
import com.dinoe.clockwise.alarm.AlarmScheduler
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
        CoroutineScope(Dispatchers.IO).launch {
            AlarmScheduler.rescheduleAllEnabled(this@ClockwiseApp)
        }
    }
}
