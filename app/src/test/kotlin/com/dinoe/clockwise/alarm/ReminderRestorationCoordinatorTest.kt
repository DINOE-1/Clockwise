package com.dinoe.clockwise.alarm

import android.app.AlarmManager
import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReminderRestorationCoordinatorTest {

    @Test
    fun concurrentRequestsShareASingleRestoration() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val coordinator = ReminderRestorationCoordinator(backgroundScope) {
            runs += 1
            entered.complete(Unit)
            gate.await()
        }

        val first = async { coordinator.restore(ReminderRestorationTrigger.BOOT_COMPLETED) }
        entered.await()
        val second = async { coordinator.restore(ReminderRestorationTrigger.TIME_CHANGED) }
        runCurrent()
        assertEquals(1, runs)

        gate.complete(Unit)
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(1, runs)
    }

    @Test
    fun aRequestAfterThePreviousRestorationStartsANewOne() = runTest {
        var runs = 0
        val coordinator = ReminderRestorationCoordinator(backgroundScope) { runs += 1 }

        assertTrue(coordinator.restore(ReminderRestorationTrigger.RECOVERY).isSuccess)
        assertTrue(coordinator.restore(ReminderRestorationTrigger.PACKAGE_REPLACED).isSuccess)
        assertEquals(2, runs)
    }

    @Test
    fun aFailingRestorationIsReportedToEveryCaller() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val failure = IllegalStateException("boom")
        var runs = 0
        val coordinator = ReminderRestorationCoordinator(backgroundScope) {
            runs += 1
            entered.complete(Unit)
            gate.await()
            throw failure
        }

        val first = async { coordinator.restore(ReminderRestorationTrigger.BOOT_COMPLETED) }
        entered.await()
        val second = async { coordinator.restore(ReminderRestorationTrigger.RECOVERY) }
        runCurrent()
        gate.complete(Unit)

        val firstResult = first.await()
        val secondResult = second.await()
        assertEquals(1, runs)
        assertTrue(firstResult.isFailure)
        assertTrue(secondResult.isFailure)
        assertSame(failure, firstResult.exceptionOrNull())
        assertSame(failure, secondResult.exceptionOrNull())
    }

    @Test
    fun routineProcessStartDoesNotScanReminders() = runTest {
        var runs = 0
        val coordinator = ReminderRestorationCoordinator(backgroundScope) { runs += 1 }

        assertTrue(coordinator.restore(ReminderRestorationTrigger.PROCESS_START).isSuccess)
        assertEquals(0, runs)
    }

    @Test
    fun onlyBootClockAndLocaleActionsRequestAFullRestoration() {
        assertEquals(
            ReminderRestorationTrigger.BOOT_COMPLETED,
            ReminderRestorationTrigger.fromAction(Intent.ACTION_BOOT_COMPLETED),
        )
        assertEquals(
            ReminderRestorationTrigger.PACKAGE_REPLACED,
            ReminderRestorationTrigger.fromAction(Intent.ACTION_MY_PACKAGE_REPLACED),
        )
        assertEquals(
            ReminderRestorationTrigger.TIME_CHANGED,
            ReminderRestorationTrigger.fromAction(Intent.ACTION_TIME_CHANGED),
        )
        assertEquals(
            ReminderRestorationTrigger.DATE_CHANGED,
            ReminderRestorationTrigger.fromAction(Intent.ACTION_DATE_CHANGED),
        )
        assertEquals(
            ReminderRestorationTrigger.TIME_ZONE_CHANGED,
            ReminderRestorationTrigger.fromAction(Intent.ACTION_TIMEZONE_CHANGED),
        )
        assertEquals(
            ReminderRestorationTrigger.LOCALE_CHANGED,
            ReminderRestorationTrigger.fromAction(Intent.ACTION_LOCALE_CHANGED),
        )
        assertEquals(
            ReminderRestorationTrigger.EXACT_ALARM_PERMISSION_CHANGED,
            ReminderRestorationTrigger.fromAction(
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            ),
        )
        assertNull(ReminderRestorationTrigger.fromAction(Intent.ACTION_SCREEN_ON))
        assertNull(ReminderRestorationTrigger.fromAction(null))
    }
}
