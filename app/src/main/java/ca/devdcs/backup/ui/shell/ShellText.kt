package ca.devdcs.backup.ui.shell

import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.ULocale
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ca.devdcs.backup.R
import ca.devdcs.backup.core.boot.ResumeSkipReason
import ca.devdcs.backup.core.logging.LogLevel
import ca.devdcs.backup.domain.model.JobStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Localized text for the shell's numbers, times and enums. Every function resolves through the
 * composition's (app-language) context and configuration, never the system locale, so a language
 * switch re-renders sizes, durations and "5 minutes ago" in place.
 */
internal object ShellText {

    @StringRes
    fun lastRunLabel(status: LastRunStatus): Int = when (status) {
        LastRunStatus.NEVER -> R.string.shell_status_never
        LastRunStatus.RUNNING -> R.string.shell_status_running
        LastRunStatus.SUCCESS -> R.string.shell_status_success
        LastRunStatus.WARNINGS -> R.string.shell_status_warnings
        LastRunStatus.FAILED -> R.string.shell_status_failed
        LastRunStatus.CANCELLED -> R.string.shell_status_cancelled
        LastRunStatus.INTERRUPTED -> R.string.shell_status_interrupted
    }

    @StringRes
    fun jobStatusLabel(status: JobStatus): Int = when (status) {
        JobStatus.RUNNING -> R.string.shell_status_running
        JobStatus.INTERRUPTED -> R.string.shell_status_interrupted
        JobStatus.COMPLETED -> R.string.shell_status_success
        JobStatus.COMPLETED_WITH_WARNINGS -> R.string.shell_status_warnings
        JobStatus.FAILED -> R.string.shell_status_failed
        JobStatus.CANCELLED -> R.string.shell_status_cancelled
    }

    @StringRes
    fun skipReason(reason: ResumeSkipReason?): Int = when (reason) {
        ResumeSkipReason.TOO_OLD -> R.string.shell_skip_too_old
        ResumeSkipReason.DESTINATION_MISSING -> R.string.shell_skip_destination_missing
        ResumeSkipReason.PROFILE_DISABLED -> R.string.shell_skip_profile_disabled
        ResumeSkipReason.NOT_FLAGGED,
        ResumeSkipReason.NOT_INTERRUPTED,
        ResumeSkipReason.NO_PROFILE_LINK,
        ResumeSkipReason.PROFILE_MISSING,
        null,
        -> R.string.shell_skip_other
    }

    @StringRes
    fun levelLabel(level: LogLevel): Int = when (level) {
        LogLevel.VERBOSE, LogLevel.DEBUG -> R.string.shell_terminal_level_debug
        LogLevel.INFO -> R.string.shell_terminal_level_info
        LogLevel.WARN, LogLevel.ERROR -> R.string.shell_terminal_level_warn
    }

    @Composable
    @ReadOnlyComposable
    fun bytes(value: Long): String = Formatter.formatShortFileSize(LocalContext.current, value.coerceAtLeast(0L))

    @Composable
    @ReadOnlyComposable
    fun rate(bytesPerSecond: Long): String = stringResource(R.string.shell_job_rate, bytes(bytesPerSecond))

    @Composable
    @ReadOnlyComposable
    fun duration(totalSeconds: Long): String {
        val parts = JobFormatting.durationParts(totalSeconds)
        return when (JobFormatting.durationStyle(parts)) {
            DurationStyle.DAYS_HOURS -> stringResource(R.string.shell_duration_days, parts.days, parts.hours)
            DurationStyle.HOURS_MINUTES -> stringResource(R.string.shell_duration_hours, parts.hours, parts.minutes)
            DurationStyle.MINUTES_SECONDS -> stringResource(R.string.shell_duration_minutes, parts.minutes, parts.seconds)
            DurationStyle.SECONDS -> stringResource(R.string.shell_duration_seconds, parts.seconds)
        }
    }

    // Locale-keyed caches: building ICU/java.time formatters per call is costly on the main thread.
    // RelativeDateTimeFormatter and DateTimeFormatter are thread-safe once built.
    private val relativeFormatters = java.util.concurrent.ConcurrentHashMap<Locale, RelativeDateTimeFormatter>()
    private val dateTimeFormatters = java.util.concurrent.ConcurrentHashMap<Locale, DateTimeFormatter>()

    @Composable
    @ReadOnlyComposable
    fun locale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()

    /** "5 minutes ago" / "in 3 hours"; a week or more away falls back to the date and time. */
    @Composable
    @ReadOnlyComposable
    fun relative(thenMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        relative(thenMillis, nowMillis, zone, locale())

    fun relative(thenMillis: Long, nowMillis: Long, zone: ZoneId, locale: Locale): String {
        val span = JobFormatting.relativeSpan(thenMillis, nowMillis)
        if (span.unit == RelativeUnit.ABSOLUTE) return dateTime(thenMillis, zone, locale)
        val formatter = relativeFormatters.getOrPut(locale) { RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale)) }
        if (span.unit == RelativeUnit.NOW) {
            return formatter.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        }
        val unit = when (span.unit) {
            RelativeUnit.MINUTES -> RelativeDateTimeFormatter.RelativeUnit.MINUTES
            RelativeUnit.HOURS -> RelativeDateTimeFormatter.RelativeUnit.HOURS
            else -> RelativeDateTimeFormatter.RelativeUnit.DAYS
        }
        val direction = if (span.future) RelativeDateTimeFormatter.Direction.NEXT else RelativeDateTimeFormatter.Direction.LAST
        return formatter.format(span.amount.toDouble(), direction, unit)
    }

    @Composable
    @ReadOnlyComposable
    fun dateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = dateTime(millis, zone, locale())

    fun dateTime(millis: Long, zone: ZoneId, locale: Locale): String =
        dateTimeFormatters.getOrPut(locale) {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
        }.format(Instant.ofEpochMilli(millis).atZone(zone))
}
