package ca.devdcs.backup.ui.profiles

import android.content.Context
import android.icu.text.ListFormatter
import ca.devdcs.backup.R
import ca.devdcs.backup.domain.model.RetentionPolicy
import ca.devdcs.backup.domain.model.ScheduleFrequency
import ca.devdcs.backup.domain.model.SchedulePolicy
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * The localized fragments schedule and retention summaries are assembled from. An interface so the
 * assembly rules in [ScheduleSummary] (day ranges, interval units, condition order) are unit-tested
 * on the JVM with a fake, while the app uses [ResourceScheduleWording] backed by string resources.
 */
interface ScheduleWording {
    fun time(hour: Int, minute: Int): String
    fun dayName(day: DayOfWeek): String
    fun dayRange(first: String, last: String): String
    fun list(items: List<String>): String
    fun everyDay(): String
    fun weekdays(): String
    fun weekends(): String
    fun daily(time: String): String
    fun weekly(days: String, time: String): String
    fun minutes(count: Int): String
    fun hours(count: Int): String
    fun hoursAndMinutes(hours: String, minutes: String): String
    fun interval(every: String, from: String): String
    fun charging(): String
    fun unmetered(): String
    fun idle(): String
    fun battery(percent: Int): String
    fun catchUp(): String
    fun withConditions(timing: String, conditions: String): String
    fun manualOnly(): String
    fun paused(timing: String): String
    fun invalid(): String
    fun keepEverything(): String
    fun keepLast(count: Int): String
    fun maxAge(days: Int): String
    /** Bare duration fragment ("30 days") used inside [keepLastAndMaxAge]. */
    fun retentionDays(days: Int): String
    fun keepLastAndMaxAge(keepLast: String, days: String): String
}

/**
 * Builds one-line, human summaries of a [SchedulePolicy] and a [RetentionPolicy] ("Mon–Fri at
 * 02:00 · while charging, on Wi-Fi"). Pure apart from the [ScheduleWording] it is given.
 */
object ScheduleSummary {

    /** Shortest run of consecutive days shown as a range ("Mon–Wed") instead of a list. */
    const val MIN_RANGE_LENGTH = 3

    private val WEEKDAYS = setOf(1, 2, 3, 4, 5)
    private val WEEKEND = setOf(6, 7)

    /**
     * Full summary for a profile: "manual only" without a schedule, the timing plus conditions
     * otherwise, prefixed as paused when [enabled] is false. [networkDestination] decides whether
     * the unmetered-network condition means anything (local destinations need no network).
     */
    fun describe(
        schedule: SchedulePolicy?,
        enabled: Boolean,
        networkDestination: Boolean,
        wording: ScheduleWording,
        firstDayOfWeek: DayOfWeek,
    ): String {
        if (schedule == null) return wording.manualOnly()
        if (!schedule.isValid) return wording.invalid()
        val timing = timing(schedule, wording, firstDayOfWeek)
        val conditions = conditions(schedule, networkDestination, wording)
        val full = if (conditions.isEmpty()) timing else wording.withConditions(timing, wording.list(conditions))
        return if (enabled) full else wording.paused(full)
    }

    /** "Daily at 02:00", "Mon, Wed at 02:00", "Every 6 h from 02:00". */
    fun timing(schedule: SchedulePolicy, wording: ScheduleWording, firstDayOfWeek: DayOfWeek): String {
        val time = wording.time(schedule.hour.coerceIn(0, 23), schedule.minute.coerceIn(0, 59))
        return when (schedule.frequency) {
            ScheduleFrequency.DAILY -> wording.daily(time)
            ScheduleFrequency.WEEKLY -> {
                val days = schedule.daysOfWeek.filter { it in 1..7 }.toSet()
                if (days.size == 7) wording.daily(time) else wording.weekly(days(days, wording, firstDayOfWeek), time)
            }
            ScheduleFrequency.INTERVAL -> wording.interval(duration(schedule.intervalMinutes ?: SchedulePolicy.MIN_INTERVAL_MINUTES, wording), time)
        }
    }

    /**
     * Selected ISO days (1 = Monday) in the locale's week order: all seven, Monday–Friday and the
     * weekend get their own words; other selections list the days, collapsing runs of
     * [MIN_RANGE_LENGTH] or more consecutive days (wrapping around the week) into "first–last".
     */
    fun days(isoDays: Set<Int>, wording: ScheduleWording, firstDayOfWeek: DayOfWeek): String {
        val valid = isoDays.filter { it in 1..7 }.toSet()
        when (valid) {
            ALL_DAYS -> return wording.everyDay()
            WEEKDAYS -> return wording.weekdays()
            WEEKEND -> return wording.weekends()
        }
        val order = (0 until 7).map { firstDayOfWeek.plus(it.toLong()) }
        val selected = order.filter { it.value in valid }
        if (selected.isEmpty()) return wording.list(emptyList())
        // Rotate so a run that wraps the end of the week (Sat, Sun, Mon) stays one run.
        val startIndex = order.indices.firstOrNull { index ->
            order[index].value in valid && order[(index + 6) % 7].value !in valid
        } ?: 0
        val rotated = (0 until 7).map { order[(startIndex + it) % 7] }
        val runs = ArrayList<MutableList<DayOfWeek>>()
        var current: MutableList<DayOfWeek>? = null
        for (day in rotated) {
            if (day.value in valid) {
                current = current ?: ArrayList<DayOfWeek>().also { runs += it }
                current.add(day)
            } else {
                current = null
            }
        }
        val parts = runs.flatMap { run ->
            if (run.size >= MIN_RANGE_LENGTH) {
                listOf(wording.dayRange(wording.dayName(run.first()), wording.dayName(run.last())))
            } else {
                run.map { wording.dayName(it) }
            }
        }
        return wording.list(parts)
    }

    /** "45 min", "6 h", "1 h 30 min". */
    fun duration(totalMinutes: Int, wording: ScheduleWording): String {
        val minutes = totalMinutes.coerceAtLeast(0)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0 -> wording.minutes(rest)
            rest == 0 -> wording.hours(hours)
            else -> wording.hoursAndMinutes(wording.hours(hours), wording.minutes(rest))
        }
    }

    /** Device conditions in a fixed order: network, charging, idle, battery, catch-up. */
    fun conditions(schedule: SchedulePolicy, networkDestination: Boolean, wording: ScheduleWording): List<String> = buildList {
        if (networkDestination && schedule.requireUnmetered) add(wording.unmetered())
        if (schedule.requireCharging) add(wording.charging())
        if (schedule.requireDeviceIdle) add(wording.idle())
        schedule.minBatteryPercent?.let { add(wording.battery(it)) }
        if (schedule.runOnBootIfMissed) add(wording.catchUp())
    }

    /** "Keeps everything", "Keeps the last 5", "Keeps 30 days", or both limits. */
    fun retention(policy: RetentionPolicy, wording: ScheduleWording): String {
        val keepLast = policy.keepLast
        val maxAge = policy.maxAgeDays
        return when {
            keepLast == null && maxAge == null -> wording.keepEverything()
            maxAge == null -> wording.keepLast(keepLast ?: 0)
            keepLast == null -> wording.maxAge(maxAge)
            else -> wording.keepLastAndMaxAge(wording.keepLast(keepLast), wording.retentionDays(maxAge))
        }
    }

    private val ALL_DAYS = (1..7).toSet()
}

/**
 * [ScheduleWording] backed by `strings_profiles.xml`. Times follow the user's 12/24-hour setting,
 * day names and list joining follow [locale] (the in-app language).
 */
class ResourceScheduleWording(
    private val context: Context,
    private val locale: Locale,
) : ScheduleWording {

    private val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
    private val resources = context.resources

    override fun time(hour: Int, minute: Int): String {
        val calendar = Calendar.getInstance(locale).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return timeFormat.format(calendar.time)
    }

    override fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, locale)
    override fun dayRange(first: String, last: String): String = context.getString(R.string.profiles_sched_day_range, first, last)
    override fun list(items: List<String>): String = ListFormatter.getInstance(locale).format(items)
    override fun everyDay(): String = context.getString(R.string.profiles_sched_every_day)
    override fun weekdays(): String = context.getString(R.string.profiles_sched_weekdays)
    override fun weekends(): String = context.getString(R.string.profiles_sched_weekends)
    override fun daily(time: String): String = context.getString(R.string.profiles_sched_daily, time)
    override fun weekly(days: String, time: String): String = context.getString(R.string.profiles_sched_weekly, days, time)
    override fun minutes(count: Int): String = resources.getQuantityString(R.plurals.profiles_sched_minutes, count, count)
    override fun hours(count: Int): String = resources.getQuantityString(R.plurals.profiles_sched_hours, count, count)
    override fun hoursAndMinutes(hours: String, minutes: String): String =
        context.getString(R.string.profiles_sched_hours_minutes, hours, minutes)
    override fun interval(every: String, from: String): String = context.getString(R.string.profiles_sched_interval, every, from)
    override fun charging(): String = context.getString(R.string.profiles_sched_cond_charging)
    override fun unmetered(): String = context.getString(R.string.profiles_sched_cond_unmetered)
    override fun idle(): String = context.getString(R.string.profiles_sched_cond_idle)
    override fun battery(percent: Int): String = context.getString(R.string.profiles_sched_cond_battery, percent)
    override fun catchUp(): String = context.getString(R.string.profiles_sched_cond_catch_up)
    override fun withConditions(timing: String, conditions: String): String =
        context.getString(R.string.profiles_sched_with_conditions, timing, conditions)
    override fun manualOnly(): String = context.getString(R.string.profiles_sched_manual)
    override fun paused(timing: String): String = context.getString(R.string.profiles_sched_paused, timing)
    override fun invalid(): String = context.getString(R.string.profiles_sched_invalid)
    override fun keepEverything(): String = context.getString(R.string.profiles_retention_keep_all)
    override fun keepLast(count: Int): String = resources.getQuantityString(R.plurals.profiles_retention_keep_last, count, count)
    override fun maxAge(days: Int): String = resources.getQuantityString(R.plurals.profiles_retention_max_age, days, days)
    override fun retentionDays(days: Int): String = resources.getQuantityString(R.plurals.profiles_retention_days, days, days)
    override fun keepLastAndMaxAge(keepLast: String, days: String): String =
        context.getString(R.string.profiles_retention_both, keepLast, days)
}
