package ca.devdcs.backup.ui.shell

import ca.devdcs.backup.core.progress.JobKind
import ca.devdcs.backup.core.progress.JobPhase
import ca.devdcs.backup.core.progress.JobProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobFormattingTest {

    private val start = 1_000_000L

    private fun job(
        processedBytes: Long = 0,
        totalBytes: Long = 0,
        processedItems: Int = 0,
        totalItems: Int = 0,
        rate: Long = 0,
        phase: JobPhase = JobPhase.RUNNING,
        updatedAt: Long = start,
    ) = JobProgress(
        jobId = "j",
        kind = JobKind.BACKUP,
        title = "Phone",
        phase = phase,
        processedBytes = processedBytes,
        totalBytes = totalBytes,
        processedItems = processedItems,
        totalItems = totalItems,
        bytesPerSecond = rate,
        startedAtMillis = start,
        updatedAtMillis = updatedAt,
    )

    @Test
    fun durationSplitsIntoDaysHoursMinutesSeconds() {
        assertEquals(DurationParts(0, 0, 0, 0), JobFormatting.durationParts(0))
        assertEquals(DurationParts(0, 0, 0, 59), JobFormatting.durationParts(59))
        assertEquals(DurationParts(0, 0, 1, 1), JobFormatting.durationParts(61))
        assertEquals(DurationParts(0, 1, 0, 5), JobFormatting.durationParts(3_605))
        assertEquals(DurationParts(2, 3, 4, 5), JobFormatting.durationParts(2 * 86_400L + 3 * 3_600 + 4 * 60 + 5))
        assertEquals(DurationParts(0, 0, 0, 0), JobFormatting.durationParts(-10))
    }

    @Test
    fun durationStylePicksTheTwoLargestUnits() {
        assertEquals(DurationStyle.SECONDS, JobFormatting.durationStyle(JobFormatting.durationParts(12)))
        assertEquals(DurationStyle.MINUTES_SECONDS, JobFormatting.durationStyle(JobFormatting.durationParts(250)))
        assertEquals(DurationStyle.HOURS_MINUTES, JobFormatting.durationStyle(JobFormatting.durationParts(3_700)))
        assertEquals(DurationStyle.DAYS_HOURS, JobFormatting.durationStyle(JobFormatting.durationParts(90_000)))
    }

    @Test
    fun etaUsesInstantRateFirst() {
        val p = job(processedBytes = 400, totalBytes = 1_000, rate = 100, updatedAt = start + 10_000)
        assertEquals(6L, JobFormatting.etaSeconds(p, p.updatedAtMillis))
    }

    @Test
    fun etaFallsBackToAverageByteRate() {
        // 250 of 1000 bytes in 10 s → 30 s for the remaining 750.
        val p = job(processedBytes = 250, totalBytes = 1_000, rate = 0, updatedAt = start + 10_000)
        assertEquals(30L, JobFormatting.etaSeconds(p, p.updatedAtMillis))
    }

    @Test
    fun etaFallsBackToItemRateWhenSizesAreUnknown() {
        // 5 of 20 items in 10 s → 30 s for the remaining 15.
        val p = job(processedItems = 5, totalItems = 20, updatedAt = start + 10_000)
        assertEquals(30L, JobFormatting.etaSeconds(p, p.updatedAtMillis))
    }

    @Test
    fun etaIsUnknownWithoutEvidenceOrTooEarly() {
        assertNull(JobFormatting.etaSeconds(job(), start + 60_000))
        // Average estimates need a few seconds of history.
        assertNull(JobFormatting.etaSeconds(job(processedBytes = 10, totalBytes = 100), start + 1_000))
        assertNull(JobFormatting.etaSeconds(job(processedBytes = 10, totalBytes = 100, phase = JobPhase.COMPLETED), start + 60_000))
    }

    @Test
    fun etaIsZeroWhenEverythingIsProcessedAndUnknownWhenAbsurd() {
        assertEquals(0L, JobFormatting.etaSeconds(job(processedBytes = 100, totalBytes = 100, rate = 0), start + 5_000))
        // 1 byte in 10 s of a 10 TB job: a stalled estimate is not shown.
        assertNull(JobFormatting.etaSeconds(job(processedBytes = 1, totalBytes = 10_000_000_000_000L), start + 10_000))
    }

    @Test
    fun elapsedNeverNegative() {
        assertEquals(0L, JobFormatting.elapsedSeconds(job(), start - 5_000))
        assertEquals(65L, JobFormatting.elapsedSeconds(job(), start + 65_400))
    }

    @Test
    fun averageRateAndPercent() {
        assertEquals(1_000L, JobFormatting.averageBytesPerSecond(10_000, 10_000))
        assertNull(JobFormatting.averageBytesPerSecond(0, 10_000))
        assertNull(JobFormatting.averageBytesPerSecond(10_000, 0))
        assertNull(JobFormatting.percent(job()))
        assertEquals(40, JobFormatting.percent(job(processedBytes = 400, totalBytes = 1_000)))
        assertEquals(25, JobFormatting.percent(job(processedItems = 1, totalItems = 4)))
    }

    @Test
    fun relativeSpanBucketsRoundDown() {
        val now = 10_000_000_000L
        assertEquals(RelativeSpan(RelativeUnit.NOW, 0, false), JobFormatting.relativeSpan(now - 30_000, now))
        assertEquals(RelativeSpan(RelativeUnit.MINUTES, 59, true), JobFormatting.relativeSpan(now + 59 * 60_000 + 59_000, now))
        assertEquals(RelativeSpan(RelativeUnit.HOURS, 3, false), JobFormatting.relativeSpan(now - 3 * 3_600_000 - 1, now))
        assertEquals(RelativeSpan(RelativeUnit.DAYS, 2, true), JobFormatting.relativeSpan(now + 2 * 86_400_000L + 5, now))
        assertEquals(RelativeUnit.ABSOLUTE, JobFormatting.relativeSpan(now - 8 * 86_400_000L, now).unit)
    }
}
