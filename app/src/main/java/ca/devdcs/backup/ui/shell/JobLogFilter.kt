package ca.devdcs.backup.ui.shell

import ca.devdcs.backup.core.logging.LogLevel
import ca.devdcs.backup.core.logging.LogLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The time span a job occupied, used to scope log lines to it; [endedAtMillis] null = still running. */
data class JobWindow(
    val jobId: String,
    val title: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
)

/**
 * Selects the in-app log lines that belong to one job. [LogLine] carries no job id, so lines are
 * attributed by time: everything logged while the job ran is shown. When another job overlapped
 * that window the time alone is ambiguous, so only lines naming the job (its id or title) are kept.
 * Lines that name the job always qualify, even outside its window (e.g. the worker's start line).
 */
object JobLogFilter {

    /** Engine set-up lines are logged a moment before the first progress publish. */
    const val LEAD_IN_MILLIS: Long = 2_000L

    /** Cleanup and history writes land shortly after the terminal publish. */
    const val TAIL_MILLIS: Long = 3_000L

    /**
     * @param target the job to scope to, or null for every line (all-jobs view).
     * @param others every other job known to the monitor, used to detect overlap.
     */
    fun filter(
        lines: List<LogLine>,
        target: JobWindow?,
        others: List<JobWindow>,
        minLevel: LogLevel,
    ): List<LogLine> {
        val levelOk: (LogLine) -> Boolean = { it.level >= minLevel }
        if (target == null) return lines.filter(levelOk)
        val shared = others.any { it.jobId != target.jobId && overlaps(it, target) }
        return lines.filter { line ->
            levelOk(line) && (mentions(line, target) || (!shared && inWindow(line.timestampMillis, target)))
        }
    }

    /** Lines that only identify the job by id (used when no window is known at all). */
    fun mentioning(lines: List<LogLine>, jobId: String, minLevel: LogLevel): List<LogLine> =
        lines.filter { it.level >= minLevel && (it.message.contains(jobId) || it.throwable?.contains(jobId) == true) }

    fun inWindow(timestampMillis: Long, window: JobWindow): Boolean {
        val start = window.startedAtMillis - LEAD_IN_MILLIS
        val end = window.endedAtMillis?.plus(TAIL_MILLIS) ?: Long.MAX_VALUE
        return timestampMillis in start..end
    }

    fun overlaps(a: JobWindow, b: JobWindow): Boolean {
        val aEnd = a.endedAtMillis ?: Long.MAX_VALUE
        val bEnd = b.endedAtMillis ?: Long.MAX_VALUE
        return a.startedAtMillis <= bEnd && b.startedAtMillis <= aEnd
    }

    fun mentions(line: LogLine, window: JobWindow): Boolean {
        if (line.message.contains(window.jobId) || line.throwable?.contains(window.jobId) == true) return true
        val title = window.title?.trim().orEmpty()
        // Very short titles ("A", "Pix") would match unrelated words; require something distinctive.
        return title.length >= MIN_TITLE_MATCH_LENGTH && line.message.contains(title)
    }

    private const val MIN_TITLE_MATCH_LENGTH = 4

    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT)

    /** One terminal / export line: `12:03:44.120 I/BackupEngine: message`. Technical, not localized. */
    fun render(line: LogLine, zone: ZoneId): String {
        val time = timeFormat.format(Instant.ofEpochMilli(line.timestampMillis).atZone(zone))
        val head = "$time ${levelLetter(line.level)}/${line.tag}: ${line.message}"
        val trace = line.throwable
        return if (trace.isNullOrBlank()) head else head + "\n" + trace.trimEnd()
    }

    fun levelLetter(level: LogLevel): Char = when (level) {
        LogLevel.VERBOSE -> 'V'
        LogLevel.DEBUG -> 'D'
        LogLevel.INFO -> 'I'
        LogLevel.WARN -> 'W'
        LogLevel.ERROR -> 'E'
    }

    /**
     * Joins [lines] for copy/share, keeping the newest lines when the text would exceed [maxChars]
     * (clipboard and intent extras travel through a ~1 MB binder buffer).
     */
    fun export(header: String, lines: List<LogLine>, zone: ZoneId, maxChars: Int = MAX_EXPORT_CHARS): String {
        val rendered = ArrayList<String>(lines.size)
        var budget = maxChars - header.length - 1
        for (line in lines.asReversed()) {
            val text = render(line, zone)
            if (text.length + 1 > budget) break
            rendered += text
            budget -= text.length + 1
        }
        rendered.reverse()
        return buildString {
            append(header)
            append('\n')
            rendered.forEach { append(it).append('\n') }
        }
    }

    const val MAX_EXPORT_CHARS: Int = 200_000
}
