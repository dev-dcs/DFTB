package ca.devdcs.backup.core.pipeline.engine

/**
 * Smoothed bytes-per-second estimate (exponentially weighted) fed with cumulative byte counts.
 * Exists so the notification's speed/ETA does not jump wildly when a network stalls for a moment
 * or a fast tar of tiny files bursts. Not thread-safe; the engine samples it from one ticker.
 */
class ThroughputMeter(
    private val smoothing: Double = DEFAULT_SMOOTHING,
    private val minSampleMillis: Long = MIN_SAMPLE_MILLIS,
) {
    init {
        require(smoothing > 0.0 && smoothing <= 1.0) { "smoothing must be in (0, 1]" }
    }

    private var lastAt: Long = -1L
    private var lastBytes: Long = 0L
    private var rate: Double = 0.0
    private var hasRate = false

    /** Feed the cumulative [totalBytes] at [nowMillis]; returns the current smoothed rate. */
    fun sample(nowMillis: Long, totalBytes: Long): Long {
        if (lastAt < 0L) {
            lastAt = nowMillis
            lastBytes = totalBytes
            return 0L
        }
        val elapsed = nowMillis - lastAt
        if (elapsed < minSampleMillis) return current()
        // A retry rewinds the cumulative count; treat it as a fresh baseline, not negative speed.
        val delta = (totalBytes - lastBytes).coerceAtLeast(0L)
        val instant = delta * 1000.0 / elapsed
        rate = if (hasRate) rate + smoothing * (instant - rate) else instant
        hasRate = true
        lastAt = nowMillis
        lastBytes = totalBytes
        return current()
    }

    fun current(): Long = if (hasRate) rate.toLong().coerceAtLeast(0L) else 0L

    fun reset() {
        lastAt = -1L
        lastBytes = 0L
        rate = 0.0
        hasRate = false
    }

    companion object {
        const val DEFAULT_SMOOTHING = 0.3
        const val MIN_SAMPLE_MILLIS = 100L
    }
}
