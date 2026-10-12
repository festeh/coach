package com.example.coach_android.data.websocket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectSchedulerTest {
    @Test
    fun duplicateFailuresScheduleOneRetryWithJitter() = runTest {
        val reconnect = ReconnectScheduler(backgroundScope, 2000) { 375 }
        var calls = 0

        assertEquals(2375L, reconnect.schedule { calls++ })
        assertNull(reconnect.schedule { calls++ })
        assertEquals(1, reconnect.attempts)
        advanceTimeBy(2374)
        runCurrent()
        assertEquals(0, calls)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun backoffCapsWithoutGivingUp() = runTest {
        val reconnect = ReconnectScheduler(backgroundScope, 2000) { 0 }
        var calls = 0

        for (delayMs in listOf(2000L, 4000L, 8000L, 16000L, 32000L, 64000L, 128000L, 128000L, 128000L)) {
            assertEquals(delayMs, reconnect.schedule { calls++ })
            advanceTimeBy(delayMs)
            runCurrent()
        }

        assertEquals(9, calls)
        assertEquals(9, reconnect.attempts)
    }

    @Test
    fun resetCancelsPendingRetryAndRestartsBackoff() = runTest {
        val reconnect = ReconnectScheduler(backgroundScope, 2000) { 0 }
        var oldCalls = 0
        var newCalls = 0
        reconnect.schedule { oldCalls++ }
        advanceTimeBy(1000)

        reconnect.reset()
        assertEquals(0, reconnect.attempts)
        assertEquals(2000L, reconnect.schedule { newCalls++ })
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(0, oldCalls)
        assertEquals(0, newCalls)
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(0, oldCalls)
        assertEquals(1, newCalls)
    }

    @Test
    fun immediateFailureCanScheduleTheNextRetry() = runTest {
        val reconnect = ReconnectScheduler(backgroundScope, 2000) { 0 }
        var nextDelay: Long? = null
        var calls = 0
        reconnect.schedule {
            nextDelay = reconnect.schedule { calls++ }
        }

        advanceTimeBy(2000)
        runCurrent()
        assertEquals(4000L, nextDelay)
        advanceTimeBy(4000)
        runCurrent()
        assertEquals(1, calls)
    }

    @Test
    fun cancellingTheOwnerStopsRetries() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val reconnect = ReconnectScheduler(scope, 2000) { 0 }
        var calls = 0
        reconnect.schedule { calls++ }

        scope.cancel()
        assertNull(reconnect.schedule { calls++ })
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(0, calls)
    }
}
