package ca.devdcs.backup.ui.shell

import ca.devdcs.backup.core.progress.JobProgress

/** A non-negative duration split for display; [days] absorbs everything past 24 hours. */
data class DurationParts(val days: Long, val hours: Int, val minutes: Int, val seconds: Int)

/** Which two-unit pattern a duration is shown with ("2 d 3 h", "1 h 5 min", "4 min 9 s", "12 s"). */
enum class DurationStyle { DAYS_HOURS, HOURS_MINUTES, MINUTES_SECONDS, SECONDS }

/** Unit chosen for a "5 minutes ago" / "in 3 hours" style label. */
enum class RelativeUnit { NOW, MINUTES, HOURS, DAYS, ABSOLUTE }

/** Signed relative distance between two instants, already bucketed to one [unit]. */
data class RelativeSpan(val unit: RelativeUnit, val amount: Long, val future: Boolean)

/**
 * Pure number crunching behind every progress, ETA, throughput and "when" label of the shell.
 * Exists so the rounding and fallback rules are unit-tested; the composable layer only maps the
 * results onto string resources, `Formatter.formatShortFileSize` and ICU relative-time text.
 */
object JobFormatting {

    /** Estimates beyond this are noise (a stalled transfer); shown as "estimating" instead. */
    const val MAX_ETA_SECONDS: Long = 30L * 24 * 60 * 60

    /** Below this much elapsed time an average-rate estimate is too jumpy to show. */
    const val MIN_ELAPSED_FOR_AVERAGE_MILLIS: Long = 3_000L

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
    private const val WEEK = 7 * DAY

    fun durationParts(totalSeconds: Long): DurationParts {
        val s = totalSeconds.coerceAtLeast(0L)
        return DurationParts(
            days = s / 86_400,
            hours = ((s % 86_400) / 3_600).toInt(),
            minutes = ((s % 3_600) / 60).toInt(),
            seconds = (s % 60).toInt(),
        )
    }

    fun durationStyle(parts: DurationParts): DurationStyle = when {
        parts.days > 0 -> DurationStyle.DAYS_HOURS
        parts.hours > 0 -> DurationStyle.HOURS_MINUTES
        parts.minutes > 0 -> DurationStyle.MINUTES_SECONDS
        else -> DurationStyle.SECONDS
    }

    /** Whole seconds since the job started; never negative even with a skewed clock. */
    fun elapsedSeconds(progress: JobProgress, nowMillis: Long): Long =
        ((nowMillis - progress.startedAtMillis).coerceAtLeast(0L)) / 1_000

    /**
     * Seconds left, trying in order: the engine's instantaneous byte rate, the average byte rate
     * since start, then the average item rate when only item counts are known. Null when nothing
     * supports an estimate (indeterminate job, nothing processed yet, or an absurd result).
     */
    fun etaSeconds(progress: JobProgress, nowMillis: Long): Long? {
        if (progress.isTerminal) return null
        val elapsedMillis = (nowMillis - progress.startedAtMillis).coerceAtLeast(0L)
        val estimate: Long? = when {
            progress.totalBytes > 0 && progress.processedBytes >= progress.totalBytes -> 0L
            progress.totalBytes > 0 && progress.bytesPerSecond > 0 ->
                (progress.totalBytes - progress.processedBytes) / progress.bytesPerSecond
            progress.totalBytes > 0 && progress.processedBytes > 0 && elapsedMillis >= MIN_ELAPSED_FOR_AVERAGE_MILLIS ->
                scaled(elapsedMillis, progress.totalBytes - progress.processedBytes, progress.processedBytes)
            progress.totalBytes <= 0 && progress.totalItems > 0 && progress.processedItems > 0 &&
                elapsedMillis >= MIN_ELAPSED_FOR_AVERAGE_MILLIS ->
                scaled(elapsedMillis, (progress.totalItems - progress.processedItems).toLong().coerceAtLeast(0L), progress.processedItems.toLong())
            else -> null
        }
        return estimate?.takeIf { it in 0..MAX_ETA_SECONDS }
    }

    /** Average bytes per second since start, for a finished job's summary; null when unknown. */
    fun averageBytesPerSecond(processedBytes: Long, durationMillis: Long): Long? {
        if (processedBytes <= 0 || durationMillis <= 0) return null
        return (processedBytes.toDouble() * 1_000 / durationMillis).toLong().takeIf { it > 0 }
    }

    /** Percentage label value, or null while the job's size is unknown. */
    fun percent(progress: JobProgress): Int? = if (progress.indeterminate) null else progress.percent.coerceIn(0, 100)

    /**
     * Buckets `then - now`: under a minute is NOW, then whole minutes, hours and days (rounded
     * down so "in 59 minutes" never reads "in 1 hour"), and a week or more falls back to an
     * absolute date.
     */
    fun relativeSpan(thenMillis: Long, nowMillis: Long): RelativeSpan {
        val delta = thenMillis - nowMillis
        val future = delta > 0
        val magnitude = if (delta == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(delta)
        return when {
            magnitude < MINUTE -> RelativeSpan(RelativeUnit.NOW, 0, future)
            magnitude < HOUR -> RelativeSpan(RelativeUnit.MINUTES, magnitude / MINUTE, future)
            magnitude < DAY -> RelativeSpan(RelativeUnit.HOURS, magnitude / HOUR, future)
            magnitude < WEEK -> RelativeSpan(RelativeUnit.DAYS, magnitude / DAY, future)
            else -> RelativeSpan(RelativeUnit.ABSOLUTE, 0, future)
        }
    }

    /** remaining × elapsed / done, in seconds, without Long overflow for multi-terabyte jobs. */
    private fun scaled(elapsedMillis: Long, remaining: Long, done: Long): Long? {
        if (done <= 0) return null
        val seconds = elapsedMillis.toDouble() / 1_000 * remaining.toDouble() / done.toDouble()
        return if (seconds.isFinite() && seconds <= MAX_ETA_SECONDS) seconds.toLong() else null
    }
}
