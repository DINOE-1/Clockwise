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
                if (!test && alarm?.enabled != true) return@launch

                val name = alarm?.name
                    ?: intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_NAME).orEmpty()
                val note = alarm?.note
                    ?: intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_NOTE).orEmpty()
                val time = alarm?.time
                    ?: intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_TIME, 0)

                // The snooze has now fired. Clear the persisted snooze before the service starts
                // ringing so the service's one-shot disable can take effect. Without this the row
                // would keep a stale non-null snoozedUntil forever.
                if (snooze) alarmDao.clearSnooze(alarmId)

                // Resolve "default" here, on the IO thread, so the service can start playback
                // without reading DataStore on its main thread.
                val ringtone = AlarmSounds.resolve(
                    context = appContext,
                    ringtone = alarm?.ringtone ?: Alarm.DEFAULT_RINGTONE,
                )

                val started = startAlarmService(
                    context = appContext,
                    alarmId = alarmId,
                    name = name,
                    note = note,
                    time = time,
                    ringtone = ringtone,
                    oneShot = !test,
                    snooze = snooze,
                )
                if (!started && snooze && alarm != null) {
                    // The foreground service was rejected; put the alarm back into a usable
                    // state instead of leaving it enabled with no scheduled PendingIntent.
                    AlarmScheduler.schedule(appContext, alarm.copy(snoozedUntil = null))
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
        ringtone: String,
        oneShot: Boolean,
        snooze: Boolean,
    ): Boolean {
        val serviceIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START
            putExtra(AlarmService.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmService.EXTRA_NAME, name)
            putExtra(AlarmService.EXTRA_NOTE, note)
            putExtra(AlarmService.EXTRA_TIME, time)
            putExtra(AlarmService.EXTRA_RINGTONE, ringtone)
            putExtra(AlarmScheduler.EXTRA_ONE_SHOT, oneShot)
            putExtra(AlarmScheduler.EXTRA_SNOOZE, snooze)
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
