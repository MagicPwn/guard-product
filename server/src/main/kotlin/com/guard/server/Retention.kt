package com.guard.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Enforces time-bounded data that must disappear even without a matching read request. */
class RetentionScheduler(
    private val screenshots: ScreenshotRepository,
    private val calls: CallRepository,
    private val parentSessions: ParentSessionRepository
) {
    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        delay(15_000)
        while (isActive) {
            try {
                screenshots.cleanupExpired()
                calls.expireStale()
                parentSessions.cleanupRetired()
            } catch (exception: Exception) {
                log.warn("Retention cleanup failed; the next scheduled pass will retry", exception)
            }
            delay(60_000)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RetentionScheduler::class.java)
    }
}
