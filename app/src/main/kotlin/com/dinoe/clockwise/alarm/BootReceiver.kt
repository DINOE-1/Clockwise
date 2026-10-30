package com.dinoe.clockwise.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.dinoe.clockwise.ClockwiseApp
import kotlinx.coroutines.launch

/**
 * Restores alarms and reminders after boot, a package replacement, a clock/date/time-zone/locale
 * change, or an exact-alarm permission change.
 *
 * The broadcast actions are mapped to a [ReminderRestorationTrigger], so an action that cannot
 * invalidate scheduled reminders never starts a scan. The work runs on the application scope and the
 * `goAsync()` result stays pending until the coordinated reminder restoration - whether this
 * receiver started it or joined one that was already running - has finished.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val trigger = ReminderRestorationTrigger.fromAction(action) ?: return
        val app = context.applicationContext as ClockwiseApp
        val pendingResult = goAsync()
        app.applicationScope.launch {
            try {
                try {
                    AlarmScheduler.rescheduleAllEnabled(app)
                } catch (e: Exception) {
                    Log.e(TAG, "Unable to restore alarms for $action", e)
                }
                app.reminderRestoration.restore(trigger).onFailure { error ->
                    Log.e(TAG, "Unable to restore event reminders for $action", error)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
