package io.github.bbzq.feats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticCheckThrottleTest {
    private val intervalMillis = 24 * 60 * 60 * 1_000L

    @Test
    fun firstRequestIsAllowedAndConcurrentWindowIsBlocked() {
        var now = 1_000L
        var persistedTimestamp: Long? = null
        val throttle = AutomaticCheckThrottle(intervalMillis)

        assertTrue(throttle.tryAcquire({ persistedTimestamp }, { persistedTimestamp = it }) { now })
        assertFalse(throttle.tryAcquire({ persistedTimestamp }, { persistedTimestamp = it }) { now })

        now += intervalMillis - 1
        assertFalse(throttle.tryAcquire({ persistedTimestamp }, { persistedTimestamp = it }) { now })
    }

    @Test
    fun requestAtIntervalBoundaryIsAllowed() {
        var now = 1_000L
        var persistedTimestamp: Long? = null
        val throttle = AutomaticCheckThrottle(intervalMillis)

        assertTrue(throttle.tryAcquire({ persistedTimestamp }, { persistedTimestamp = it }) { now })
        now += intervalMillis

        assertTrue(throttle.tryAcquire({ persistedTimestamp }, { persistedTimestamp = it }) { now })
    }

    @Test
    fun clockRollbackDoesNotKeepTheGateClosedForever() {
        var now = 10_000L
        var persistedTimestamp: Long? = null
        val throttle = AutomaticCheckThrottle(intervalMillis)

        assertTrue(throttle.tryAcquire({ persistedTimestamp }, { persistedTimestamp = it }) { now })
        now = 1_000L

        assertTrue(throttle.tryAcquire({ persistedTimestamp }, { persistedTimestamp = it }) { now })
    }

    @Test
    fun newThrottleInstanceStillReadsPersistedTimestamp() {
        var now = 10_000L
        var persistedTimestamp: Long? = null
        val firstProcessThrottle = AutomaticCheckThrottle(intervalMillis)

        assertTrue(
            firstProcessThrottle.tryAcquire(
                { persistedTimestamp },
                { persistedTimestamp = it },
            ) { now },
        )

        now += 1_000L
        val restartedProcessThrottle = AutomaticCheckThrottle(intervalMillis)

        assertFalse(
            restartedProcessThrottle.tryAcquire(
                { persistedTimestamp },
                { persistedTimestamp = it },
            ) { now },
        )
    }
}
