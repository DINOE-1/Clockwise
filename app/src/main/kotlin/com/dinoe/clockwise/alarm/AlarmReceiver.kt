package com.dinoe.clockwise.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.data.Alarm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
                if (alarmId <= 0L) return@launch

                val snooze = intent.getBooleanExtra(AlarmScheduler.EXTRA_SNOOZE, false)
                val test = intent.getBooleanExtra(AlarmScheduler.EXTRA_TEST, false)
                val alarmDao = (appContext as ClockwiseApp).database.alarmDao()
                val alarm = alarmDao.getById(alarmId)

                if (!test && alarm == null) return@launch
                if (!snooze && !test && alarm?.enabled != true) return@launch

                val name = alarm?.name
                    ?: intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_NAME).orEmpty()
                val note = alarm?.note
                    ?: intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_NOTE).orEmpty()
                val time = alarm?.time
                    ?: intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_TIME, 0)

                val started = startAlarmService(appContext, alarmId, name, note, time)
                if (started && !snooze && !test && alarm != null) {
                    // A plain alarm fires once; switch it off until the user enables it again.
                    alarmDao.update(alarm.copy(enabled = false))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start the alarm", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun startAlarmService(
        context: Context,
        alarmId: Long,
        name: String,
        note: String,
        time: Int,
    ): Boolean {
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            putExtra(AlarmService.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmService.EXTRA_NAME, name)
            putExtra(AlarmService.EXTRA_NOTE, note)
            putExtra(AlarmService.EXTRA_TIME, time)
        }
        return try {
            context.startForegroundService(serviceIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Foreground service start was rejected", e)
            val alarm = Alarm(
                id = alarmId,
                time = time,
                name = name,
                note = note,
            )
            AlarmNotifier.showStartFailure(context, alarm)
            false
        }
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
