package ca.devdcs.backup.ui.shell

import ca.devdcs.backup.core.logging.LogLevel
import ca.devdcs.backup.core.logging.LogLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class JobLogFilterTest {

    private fun line(at: Long, message: String, level: LogLevel = LogLevel.INFO, tag: String = "BackupEngine", trace: String? = null) =
        LogLine(timestampMillis = at, level = level, tag = tag, message = message, throwable = trace)

    private val jobA = JobWindow("job-aaaa", "Photos", startedAtMillis = 10_000, endedAtMillis = 20_000)
    private val jobB = JobWindow("job-bbbb", "Documents", startedAtMillis = 15_000, endedAtMillis = null)
    private val jobC = JobWindow("job-cccc", "Music", startedAtMillis = 40_000, endedAtMillis = 50_000)

    @Test
    fun noTargetKeepsEveryLineAtOrAboveTheLevel() {
        val lines = listOf(line(1, "a", LogLevel.DEBUG), line(2, "b", LogLevel.INFO), line(3, "c", LogLevel.ERROR))
        assertEquals(lines, JobLogFilter.filter(lines, null, emptyList(), LogLevel.DEBUG))
        assertEquals(listOf(lines[1], lines[2]), JobLogFilter.filter(lines, null, emptyList(), LogLevel.INFO))
        assertEquals(listOf(lines[2]), JobLogFilter.filter(lines, null, emptyList(), LogLevel.WARN))
    }

    @Test
    fun soloJobGetsEverythingInsideItsPaddedWindow() {
        val before = line(5_000, "unrelated earlier")
        val leadIn = line(9_000, "planning")
        val inside = line(12_000, "uploading")
        val tail = line(22_000, "history written")
        val after = line(30_000, "later")
        val result = JobLogFilter.filter(listOf(before, leadIn, inside, tail, after), jobA, listOf(jobC), LogLevel.DEBUG)
        assertEquals(listOf(leadIn, inside, tail), result)
    }

    @Test
    fun overlappingJobsOnlyKeepLinesNamingTheJob() {
        val mine = line(16_000, "Backup job-aaaa failed")
        val byTitle = line(16_500, "Uploading Photos archive")
        val theirs = line(17_000, "Backup job-bbbb part 3")
        val anonymous = line(17_500, "Transient failure, retrying")
        val result = JobLogFilter.filter(listOf(mine, byTitle, theirs, anonymous), jobA, listOf(jobB), LogLevel.DEBUG)
        assertEquals(listOf(mine, byTitle), result)
    }

    @Test
    fun linesNamingTheJobQualifyOutsideTheWindowAndInStackTraces() {
        val workerStart = line(1_000, "Starting manual backup of p1 (job job-aaaa)", tag = "BackupWorker")
        val trace = line(25_000, "Upload failed", LogLevel.ERROR, trace = "java.io.IOException: job-aaaa/part1")
        val result = JobLogFilter.filter(listOf(workerStart, trace), jobA, emptyList(), LogLevel.DEBUG)
        assertEquals(listOf(workerStart, trace), result)
    }

    @Test
    fun shortTitlesAreNotUsedForMatching() {
        val window = JobWindow("id-1", "A", 0, 100)
        assertFalse(JobLogFilter.mentions(line(50, "A file"), window))
        assertTrue(JobLogFilter.mentions(line(50, "id-1 done"), window))
    }

    @Test
    fun overlapTreatsOpenWindowsAsRunning() {
        assertTrue(JobLogFilter.overlaps(jobA, jobB))
        assertFalse(JobLogFilter.overlaps(jobA, jobC))
        assertTrue(JobLogFilter.overlaps(jobB, jobC))
    }

    @Test
    fun mentioningMatchesIdOnly() {
        val lines = listOf(line(1, "job-aaaa queued"), line(2, "other"), line(3, "x", LogLevel.DEBUG, trace = "at job-aaaa"))
        assertEquals(listOf(lines[0]), JobLogFilter.mentioning(lines, "job-aaaa", LogLevel.INFO))
        assertEquals(listOf(lines[0], lines[2]), JobLogFilter.mentioning(lines, "job-aaaa", LogLevel.DEBUG))
    }

    @Test
    fun renderIsTechnicalAndAppendsTheTrace() {
        val text = JobLogFilter.render(line(3_723_004, "hello", LogLevel.WARN, tag = "Tag", trace = "trace\n"), ZoneOffset.UTC)
        assertEquals("01:02:03.004 W/Tag: hello\ntrace", text)
    }

    @Test
    fun exportKeepsTheNewestLinesWithinTheBudget() {
        val lines = (1..10).map { line(it * 1_000L, "line-$it") }
        val full = JobLogFilter.export("H", lines, ZoneOffset.UTC, Int.MAX_VALUE)
        assertTrue(full.startsWith("H\n"))
        assertEquals(11, full.trimEnd().lines().size)

        val oneLineLength = JobLogFilter.render(lines.last(), ZoneOffset.UTC).length + 1
        val trimmed = JobLogFilter.export("H", lines, ZoneOffset.UTC, maxChars = 2 + oneLineLength * 3)
        val kept = trimmed.trimEnd().lines().drop(1)
        assertEquals(3, kept.size)
        assertTrue(kept.last().endsWith("line-10"))
        assertTrue(kept.first().endsWith("line-8"))
    }
}
