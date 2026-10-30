package com.dinoe.clockwise.alarm

import android.app.AlarmManager
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reason a reminder restoration was requested.
 *
 * Restoring reminders is a scan of the complete event table plus one `AlarmManager` call per
 * upcoming reminder, so it is only allowed for events that can actually invalidate the scheduled
 * alarms. Routine process creation is deliberately not one of them.
 */
enum class ReminderRestorationTrigger(
    /** Whether this trigger may run the full "reschedule every upcoming reminder" scan. */
    val requiresFullRestore: Boolean,
) {
    /**
     * Routine process creation. Reminder alarms live in `AlarmManager` and survive while the
     * process is gone, so a cold start must not rescan the event table; boot, package replacement,
     * and clock/locale changes already repair them.
     */
    PROCESS_START(requiresFullRestore = false),

    BOOT_COMPLETED(requiresFullRestore = true),
    PACKAGE_REPLACED(requiresFullRestore = true),
    TIME_CHANGED(requiresFullRestore = true),
    DATE_CHANGED(requiresFullRestore = true),
    TIME_ZONE_CHANGED(requiresFullRestore = true),
    LOCALE_CHANGED(requiresFullRestore = true),

    /**
     * Granting the exact-alarm permission upgrades previously inexact reminders, so they are
     * restored as well.
     */
    EXACT_ALARM_PERMISSION_CHANGED(requiresFullRestore = true),

    /** Explicit recovery paths, for example after a rolled-back proposal write. */
    RECOVERY(requiresFullRestore = true),
    ;

    companion object {
        /** Maps a broadcast action to its trigger, or returns `null` when the action is unrelated. */
        fun fromAction(action: String?): ReminderRestorationTrigger? = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> BOOT_COMPLETED
            Intent.ACTION_MY_PACKAGE_REPLACED -> PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED -> TIME_CHANGED
            Intent.ACTION_DATE_CHANGED -> DATE_CHANGED
            Intent.ACTION_TIMEZONE_CHANGED -> TIME_ZONE_CHANGED
            Intent.ACTION_LOCALE_CHANGED -> LOCALE_CHANGED
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                EXACT_ALARM_PERMISSION_CHANGED

            else -> null
        }
    }
}

/**
 * Application-scoped owner of the reminder restoration scan.
 *
 * Several entry points can request a restoration at the same time: the boot receiver, a clock or
 * time-zone change, and the explicit recovery path after a failed write. Those requests used to run
 * the full scan concurrently, once per caller. This coordinator serializes them on a mutex and lets
 * a request that arrives while a scan is running join that scan instead of starting a second one.
 *
 * The operation itself runs on the scope handed in by the application, so a caller that goes away
 * (a cancelled ViewModel coroutine) cannot abort a restoration that other callers are waiting for.
 */
class ReminderRestorationCoordinator(
    private val scope: CoroutineScope,
    private val restoreOperation: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private var inFlight: Deferred<Result<Unit>>? = null

    /**
     * Runs the restoration, or joins the one that is already running. Suspends until the coordinated
     * operation completes, which is what keeps a receiver's `goAsync()` result pending for exactly
     * as long as the work takes.
     *
     * Failures are returned instead of thrown so that one failing scan cannot cancel the
     * application scope, while every caller still learns that its request did not succeed.
     */
    suspend fun restore(trigger: ReminderRestorationTrigger): Result<Unit> {
        if (!trigger.requiresFullRestore) return Result.success(Unit)
        val pending = mutex.withLock {
            val running = inFlight
            if (running != null && !running.isCompleted) {
                running
            } else {
                scope.async { runRestoration() }.also { created -> inFlight = created }
            }
        }
        val result = pending.await()
        mutex.withLock { if (inFlight === pending) inFlight = null }
        return result
    }

    private suspend fun runRestoration(): Result<Unit> = try {
        restoreOperation()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
