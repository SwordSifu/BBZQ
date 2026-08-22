package io.github.bbzq.feats

/**
 * Decision/state coordinator for automatic checks.
 *
 * Persistence is supplied by callbacks so this class stays pure Kotlin and
 * can be used with a fake store in unit tests.  The timestamp is read and
 * recorded when a request is admitted, before networking is started, so a
 * failed request cannot trigger a retry storm.  Access is synchronized because
 * several host callbacks can enter the checker at once.
 */
internal class AutomaticCheckThrottle(
    private val intervalMillis: Long,
) {
    private val lock = Any()

    init {
        require(intervalMillis >= 0) { "intervalMillis must not be negative" }
    }

    /** Return true exactly when this caller may start a request. */
    fun tryAcquire(
        readLastStartedAt: () -> Long?,
        writeLastStartedAt: (Long) -> Unit,
        nowMillis: () -> Long,
    ): Boolean = synchronized(lock) {
        val now = nowMillis()
        val previous = readLastStartedAt()
        val allowed = previous == null || now < previous || now - previous >= intervalMillis
        if (allowed) {
            writeLastStartedAt(now)
        }
        allowed
    }
}
