package ca.devdcs.backup.core.schedule

import ca.devdcs.backup.domain.model.ScheduleFrequency
import ca.devdcs.backup.domain.model.SchedulePolicy
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Wall-clock arithmetic for [SchedulePolicy]: when the next and the most recent "slot" of a
 * schedule are, and whether a slot is still owed. Exists as a pure object so DST transitions,
 * weekday selection and interval anchoring are unit-tested instead of discovered at 2 a.m.
 *
 * Slots are interpreted in the device zone. DAILY/WEEKLY slots fall on [SchedulePolicy.hour]:
 * [SchedulePolicy.minute] (a DST gap shifts them forward, as java.time does). INTERVAL slots are
 * anchored on today's hour:minute and repeat every [SchedulePolicy.intervalMinutes].
 */
object ScheduleMath {

    const val MINUTE_MILLIS = 60_000L
    const val HOUR_MILLIS = 60 * MINUTE_MILLIS
    const val DAY_MILLIS = 24 * HOUR_MILLIS
    const val WEEK_MILLIS = 7 * DAY_MILLIS

    /** WorkManager's minimum flex window. */
    const val MIN_FLEX_MILLIS = 5 * MINUTE_MILLIS

    /** Largest flex window used for long periods. */
    const val MAX_FLEX_MILLIS = HOUR_MILLIS

    /** How early a run may fire and still count for the upcoming slot. */
    const val MAX_EARLY_TOLERANCE_MILLIS = 15 * MINUTE_MILLIS

    /**
     * WorkManager repeat period. WEEKLY with a single day repeats weekly; with several days it
     * repeats daily and [isDue] skips the unselected days (the exact next slot is set as an
     * override after every run, the period is only the fallback).
     */
    fun periodMillis(policy: SchedulePolicy): Long = when (policy.frequency) {
        ScheduleFrequency.DAILY -> DAY_MILLIS
        ScheduleFrequency.WEEKLY -> if (validDays(policy).size == 1) WEEK_MILLIS else DAY_MILLIS
        ScheduleFrequency.INTERVAL -> intervalMillis(policy)
    }

    /** Flex window: an eighth of the period, clamped to WorkManager's 5 min floor and 1 h. */
    fun flexMillis(periodMillis: Long): Long = (periodMillis / 8).coerceIn(MIN_FLEX_MILLIS, MAX_FLEX_MILLIS).coerceAtMost(periodMillis)

    fun earlyToleranceMillis(policy: SchedulePolicy): Long = when (policy.frequency) {
        ScheduleFrequency.INTERVAL -> (intervalMillis(policy) / 4).coerceAtMost(MAX_EARLY_TOLERANCE_MILLIS)
        else -> MAX_EARLY_TOLERANCE_MILLIS
    }

    /** First slot strictly after [nowMillis]. */
    fun nextSlot(policy: SchedulePolicy, nowMillis: Long, zone: ZoneId): Long {
        val time = slotTime(policy)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (policy.frequency) {
            ScheduleFrequency.DAILY -> {
                val candidate = at(today, time, zone)
                if (candidate > nowMillis) candidate else at(today.plusDays(1), time, zone)
            }
            ScheduleFrequency.WEEKLY -> {
                val days = validDays(policy)
                (0L..7L).asSequence()
                    .map { today.plusDays(it) }
                    .filter { it.dayOfWeek in days }
                    .map { at(it, time, zone) }
                    .firstOrNull { it > nowMillis }
                    ?: at(today.plusDays(7), time, zone)
            }
            ScheduleFrequency.INTERVAL -> {
                val interval = intervalMillis(policy)
                val anchor = at(today, time, zone)
                val steps = Math.floorDiv(nowMillis - anchor, interval) + 1
                anchor + steps * interval
            }
        }
    }

    /** Latest slot at or before [nowMillis]. */
    fun previousSlot(policy: SchedulePolicy, nowMillis: Long, zone: ZoneId): Long {
        val time = slotTime(policy)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (policy.frequency) {
            ScheduleFrequency.DAILY -> {
                val candidate = at(today, time, zone)
                if (candidate <= nowMillis) candidate else at(today.minusDays(1), time, zone)
            }
            ScheduleFrequency.WEEKLY -> {
                val days = validDays(policy)
                (0L..7L).asSequence()
                    .map { today.minusDays(it) }
                    .filter { it.dayOfWeek in days }
                    .map { at(it, time, zone) }
                    .firstOrNull { it <= nowMillis }
                    ?: at(today.minusDays(7), time, zone)
            }
            ScheduleFrequency.INTERVAL -> {
                val interval = intervalMillis(policy)
                val anchor = at(today, time, zone)
                anchor + Math.floorDiv(nowMillis - anchor, interval) * interval
            }
        }
    }

    /**
     * True when the slot that is current at [nowMillis] (looking [earlyToleranceMillis] ahead, so
     * a slightly early run counts) has not been served by a run started at [lastRunAtMillis].
     * This makes duplicate firings (fallback period + override, boot catch-up) run once per slot.
     */
    fun isDue(policy: SchedulePolicy, nowMillis: Long, zone: ZoneId, lastRunAtMillis: Long?): Boolean {
        if (lastRunAtMillis == null) return true
        val tolerance = earlyToleranceMillis(policy)
        val slot = previousSlot(policy, nowMillis + tolerance, zone)
        return lastRunAtMillis < slot - tolerance
    }

    /** Selected weekdays; empty (or all invalid) falls back to every day so nothing is lost. */
    fun validDays(policy: SchedulePolicy): Set<DayOfWeek> {
        val days = policy.daysOfWeek.filter { it in 1..7 }.map { DayOfWeek.of(it) }.toSet()
        return days.ifEmpty { DayOfWeek.values().toSet() }
    }

    fun intervalMillis(policy: SchedulePolicy): Long =
        (policy.intervalMinutes ?: SchedulePolicy.MIN_INTERVAL_MINUTES).coerceAtLeast(SchedulePolicy.MIN_INTERVAL_MINUTES) * MINUTE_MILLIS

    private fun slotTime(policy: SchedulePolicy): LocalTime = LocalTime.of(policy.hour.coerceIn(0, 23), policy.minute.coerceIn(0, 59))

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Long = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}
