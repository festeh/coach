package com.example.coach_android.data.websocket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Keeps one pending retry, with capped exponential backoff and jitter. */
internal class ReconnectScheduler(
    private val scope: CoroutineScope,
    private val baseDelayMs: Long,
    private val jitterMs: () -> Long = { Random.nextLong(1000) },
) {
    @Volatile
    var attempts: Int = 0
        private set

    private var job: Job? = null

    @Synchronized
    fun schedule(reconnect: () -> Unit): Long? {
        if (!scope.isActive || job?.isActive == true) return null

        attempts++
        val delayMs = baseDelayMs * (1L shl (attempts - 1).coerceAtMost(6)) + jitterMs()
        val retry = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMs)
            synchronized(this@ReconnectScheduler) {
                if (job !== coroutineContext[Job]) return@launch
                // Clear before connecting: even an immediate failure can schedule
                // its next retry rather than being hidden by this running job.
                job = null
                reconnect()
            }
        }
        job = retry
        retry.start()
        return delayMs
    }

    @Synchronized
    fun reset() {
        job?.cancel()
        job = null
        attempts = 0
    }
}
