package com.dinoe.clockwise.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.data.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val eventId = intent.getLongExtra(ReminderScheduler.EXTRA_EVENT_ID, -1L)
                val expectedTrigger = intent.getLongExtra(
                    ReminderScheduler.EXTRA_TRIGGER_MILLIS,
                    -1L,
                )
                if (eventId <= 0L) return@launch

                val event = (appContext as ClockwiseApp).database.eventDao().getById(eventId)
                    ?: return@launch
                if (event.reminderMinutes == Event.REMINDER_OFF) return@launch
                // A stale intent from an edited event (or a rolled-back proposal) must not
                // notify with data that no longer matches the reminder it was scheduled for.
                if (event.reminderTriggerMillis() != expectedTrigger) return@launch

                ReminderNotifier.show(appContext, event)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to show the event reminder", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ReminderReceiver"
    }
}
