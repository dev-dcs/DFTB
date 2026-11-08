package ca.devdcs.backup.ui.profiles

import ca.devdcs.backup.domain.model.RetentionPolicy
import ca.devdcs.backup.domain.model.ScheduleFrequency
import ca.devdcs.backup.domain.model.SchedulePolicy
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek

class ScheduleSummaryTest {

    /** Deterministic English stand-in for the string resources. */
    private object Fake : ScheduleWording {
        override fun time(hour: Int, minute: Int) = "%02d:%02d".format(hour, minute)
        override fun dayName(day: DayOfWeek) = day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        override fun dayRange(first: String, last: String) = "$first–$last"
        override fun list(items: List<String>) = items.joinToString(", ")
        override fun everyDay() = "every day"
        override fun weekdays() = "weekdays"
        override fun weekends() = "weekends"
        override fun daily(time: String) = "Daily at $time"
        override fun weekly(days: String, time: String) = "$days at $time"
        override fun minutes(count: Int) = "$count min"
        override fun hours(count: Int) = "$count h"
        override fun hoursAndMinutes(hours: String, minutes: String) = "$hours $minutes"
        override fun interval(every: String, from: String) = "Every $every from $from"
        override fun charging() = "while charging"
        override fun unmetered() = "on unmetered networks"
        override fun idle() = "while idle"
        override fun battery(percent: Int) = "battery at least $percent%"
        override fun catchUp() = "catches up"
        override fun withConditions(timing: String, conditions: String) = "$timing · $conditions"
        override fun manualOnly() = "Manual only"
        override fun paused(timing: String) = "Paused · $timing"
        override fun invalid() = "Invalid"
        override fun keepEverything() = "Keeps everything"
        override fun keepLast(count: Int) = "Keeps the last $count"
        override fun maxAge(days: Int) = "Keeps $days days"
        override fun retentionDays(days: Int) = "$days days"
        override fun keepLastAndMaxAge(keepLast: String, days: String) = "$keepLast, none older than $days"
    }

    private val quiet = SchedulePolicy(requireUnmetered = false, runOnBootIfMissed = false)

    private fun days(vararg iso: Int, first: DayOfWeek = DayOfWeek.MONDAY) = ScheduleSummary.days(iso.toSet(), Fake, first)

    @Test
    fun `special day sets get their own words`() {
        assertEquals("every day", days(1, 2, 3, 4, 5, 6, 7))
        assertEquals("weekdays", days(1, 2, 3, 4, 5))
        assertEquals("weekends", days(6, 7))
    }

    @Test
    fun `runs of three or more collapse into ranges`() {
        assertEquals("Mon–Wed", days(1, 2, 3))
        assertEquals("Mon, Tue", days(1, 2))
        assertEquals("Mon, Wed, Fri", days(1, 3, 5))
        assertEquals("Mon–Thu, Sat", days(1, 2, 3, 4, 6))
    }

    @Test
    fun `days follow the locale's first day and ranges wrap the week`() {
        // Sunday-first locales list Sunday first.
        assertEquals("Sun, Wed", days(3, 7, first = DayOfWeek.SUNDAY))
        assertEquals("Wed, Sun", days(3, 7, first = DayOfWeek.MONDAY))
        // Fri, Sat, Sun, Mon is one run across the end of the week.
        assertEquals("Fri–Mon", days(5, 6, 7, 1))
        assertEquals("Fri–Mon", days(5, 6, 7, 1, first = DayOfWeek.SUNDAY))
    }

    @Test
    fun `invalid day numbers are ignored`() {
        assertEquals("Mon, Tue", days(0, 1, 2, 9))
    }

    @Test
    fun `durations use the largest units`() {
        assertEquals("45 min", ScheduleSummary.duration(45, Fake))
        assertEquals("6 h", ScheduleSummary.duration(360, Fake))
        assertEquals("1 h 30 min", ScheduleSummary.duration(90, Fake))
    }

    @Test
    fun `timing per frequency`() {
        assertEquals("Daily at 02:00", ScheduleSummary.timing(quiet, Fake, DayOfWeek.MONDAY))
        assertEquals(
            "Mon, Thu at 23:30",
            ScheduleSummary.timing(quiet.copy(frequency = ScheduleFrequency.WEEKLY, daysOfWeek = setOf(4, 1), hour = 23, minute = 30), Fake, DayOfWeek.MONDAY),
        )
        assertEquals(
            "Daily at 02:00",
            ScheduleSummary.timing(quiet.copy(frequency = ScheduleFrequency.WEEKLY, daysOfWeek = (1..7).toSet()), Fake, DayOfWeek.MONDAY),
        )
        assertEquals(
            "Every 1 h 30 min from 06:05",
            ScheduleSummary.timing(quiet.copy(frequency = ScheduleFrequency.INTERVAL, intervalMinutes = 90, hour = 6, minute = 5), Fake, DayOfWeek.MONDAY),
        )
    }

    @Test
    fun `conditions keep a fixed order and network only for network destinations`() {
        val all = SchedulePolicy(
            requireUnmetered = true,
            requireCharging = true,
            requireDeviceIdle = true,
            minBatteryPercent = 40,
            runOnBootIfMissed = true,
        )
        assertEquals(
            listOf("on unmetered networks", "while charging", "while idle", "battery at least 40%", "catches up"),
            ScheduleSummary.conditions(all, networkDestination = true, wording = Fake),
        )
        assertEquals(
            listOf("while charging", "while idle", "battery at least 40%", "catches up"),
            ScheduleSummary.conditions(all, networkDestination = false, wording = Fake),
        )
    }

    @Test
    fun `describe covers manual, invalid, paused and conditions`() {
        assertEquals("Manual only", ScheduleSummary.describe(null, enabled = true, networkDestination = true, wording = Fake, firstDayOfWeek = DayOfWeek.MONDAY))
        assertEquals(
            "Invalid",
            ScheduleSummary.describe(quiet.copy(hour = 30), enabled = true, networkDestination = false, wording = Fake, firstDayOfWeek = DayOfWeek.MONDAY),
        )
        assertEquals(
            "Daily at 02:00",
            ScheduleSummary.describe(quiet, enabled = true, networkDestination = true, wording = Fake, firstDayOfWeek = DayOfWeek.MONDAY),
        )
        assertEquals(
            "Paused · Daily at 02:00 · while charging",
            ScheduleSummary.describe(
                quiet.copy(requireCharging = true),
                enabled = false,
                networkDestination = false,
                wording = Fake,
                firstDayOfWeek = DayOfWeek.MONDAY,
            ),
        )
    }

    @Test
    fun `retention summaries`() {
        assertEquals("Keeps everything", ScheduleSummary.retention(RetentionPolicy.KEEP_ALL, Fake))
        assertEquals("Keeps the last 5", ScheduleSummary.retention(RetentionPolicy(keepLast = 5), Fake))
        assertEquals("Keeps 30 days", ScheduleSummary.retention(RetentionPolicy(maxAgeDays = 30), Fake))
        assertEquals("Keeps the last 5, none older than 30 days", ScheduleSummary.retention(RetentionPolicy(keepLast = 5, maxAgeDays = 30), Fake))
    }
}
