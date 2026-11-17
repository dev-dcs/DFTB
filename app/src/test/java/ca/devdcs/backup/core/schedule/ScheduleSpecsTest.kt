package ca.devdcs.backup.core.schedule

import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.ScheduleFrequency
import ca.devdcs.backup.domain.model.SchedulePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ScheduleSpecsTest {

    private val zone: ZoneId = ZoneId.of("America/Toronto")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun profile(schedule: SchedulePolicy?, enabled: Boolean = true, lastRunAt: Long? = null) = BackupProfile(
        id = "p1",
        name = "Daily",
        destinationId = "d1",
        schedule = schedule,
        createdAt = 0L,
        updatedAt = 0L,
        lastRunAt = lastRunAt,
        enabled = enabled,
    )

    // --- Slots ----------------------------------------------------------------------------------

    @Test
    fun dailyNextSlotIsTodayWhenStillAhead() {
        val policy = SchedulePolicy(hour = 2, minute = 30)
        assertEquals(at(2026, 10, 19, 2, 30), ScheduleMath.nextSlot(policy, at(2026, 10, 19, 1, 0), zone))
    }

    @Test
    fun dailyNextSlotIsTomorrowWhenPassed() {
        val policy = SchedulePolicy(hour = 2, minute = 30)
        assertEquals(at(2026, 10, 20, 2, 30), ScheduleMath.nextSlot(policy, at(2026, 10, 19, 2, 30), zone))
    }

    @Test
    fun weeklyPicksNextSelectedDay() {
        // 2026-10-19 is a Monday. Wednesday (3) and Saturday (6) selected.
        val policy = SchedulePolicy(frequency = ScheduleFrequency.WEEKLY, hour = 3, daysOfWeek = setOf(3, 6))
        assertEquals(at(2026, 10, 21, 3, 0), ScheduleMath.nextSlot(policy, at(2026, 10, 19, 12, 0), zone))
        assertEquals(at(2026, 10, 24, 3, 0), ScheduleMath.nextSlot(policy, at(2026, 10, 21, 3, 0), zone))
        assertEquals(at(2026, 10, 17, 3, 0), ScheduleMath.previousSlot(policy, at(2026, 10, 19, 12, 0), zone))
    }

    @Test
    fun weeklySameDayLaterTodayWins() {
        val policy = SchedulePolicy(frequency = ScheduleFrequency.WEEKLY, hour = 22, daysOfWeek = setOf(1))
        assertEquals(at(2026, 10, 19, 22, 0), ScheduleMath.nextSlot(policy, at(2026, 10, 19, 21, 0), zone))
        assertEquals(at(2026, 10, 26, 22, 0), ScheduleMath.nextSlot(policy, at(2026, 10, 19, 22, 1), zone))
    }

    @Test
    fun intervalSlotsAreAnchoredOnTimeOfDay() {
        val policy = SchedulePolicy(frequency = ScheduleFrequency.INTERVAL, hour = 0, minute = 0, intervalMinutes = 360)
        assertEquals(at(2026, 10, 19, 12, 0), ScheduleMath.nextSlot(policy, at(2026, 10, 19, 7, 15), zone))
        assertEquals(at(2026, 10, 19, 6, 0), ScheduleMath.previousSlot(policy, at(2026, 10, 19, 7, 15), zone))
    }

    @Test
    fun dstSpringForwardGapShiftsSlotForward() {
        // 2026-03-08 02:30 does not exist in Toronto; java.time moves it to 03:30 EDT.
        val policy = SchedulePolicy(hour = 2, minute = 30)
        val next = ScheduleMath.nextSlot(policy, at(2026, 3, 8, 0, 0), zone)
        assertEquals(LocalDateTime.of(2026, 3, 8, 3, 30).atZone(zone).toInstant().toEpochMilli(), next)
    }

    // --- Due gating ----------------------------------------------------------------------------

    @Test
    fun dueWhenNeverRun() {
        assertTrue(ScheduleMath.isDue(SchedulePolicy(), at(2026, 10, 19, 2, 0), zone, null))
    }

    @Test
    fun notDueTwiceForTheSameSlot() {
        val policy = SchedulePolicy(hour = 2)
        val lastRun = at(2026, 10, 19, 2, 1)
        assertFalse(ScheduleMath.isDue(policy, at(2026, 10, 19, 2, 20), zone, lastRun))
        assertFalse(ScheduleMath.isDue(policy, at(2026, 10, 19, 23, 0), zone, lastRun))
        assertTrue(ScheduleMath.isDue(policy, at(2026, 10, 20, 2, 0), zone, lastRun))
    }

    @Test
    fun slightlyEarlyRunCountsForUpcomingSlot() {
        val policy = SchedulePolicy(hour = 2)
        val now = at(2026, 10, 20, 1, 55)
        assertTrue(ScheduleMath.isDue(policy, now, zone, at(2026, 10, 19, 2, 0)))
        // A run started at 01:55 serves the 02:00 slot.
        assertFalse(ScheduleMath.isDue(policy, at(2026, 10, 20, 2, 5), zone, now))
    }

    @Test
    fun lateRunAfterConstraintWaitIsStillDue() {
        val policy = SchedulePolicy(hour = 2, requireCharging = true)
        assertTrue(ScheduleMath.isDue(policy, at(2026, 10, 20, 9, 0), zone, at(2026, 10, 19, 2, 0)))
    }

    @Test
    fun multiDayWeeklySkipsUnselectedDays() {
        val policy = SchedulePolicy(frequency = ScheduleFrequency.WEEKLY, hour = 2, daysOfWeek = setOf(1, 4))
        // Monday run done; fallback daily period fires Tuesday 02:00: not due.
        assertFalse(ScheduleMath.isDue(policy, at(2026, 10, 20, 2, 0), zone, at(2026, 10, 19, 2, 0)))
        // Thursday 02:00: due.
        assertTrue(ScheduleMath.isDue(policy, at(2026, 10, 22, 2, 0), zone, at(2026, 10, 19, 2, 0)))
    }

    // --- Period / flex ---------------------------------------------------------------------------

    @Test
    fun periodAndFlexPerFrequency() {
        assertEquals(ScheduleMath.DAY_MILLIS, ScheduleMath.periodMillis(SchedulePolicy()))
        assertEquals(ScheduleMath.WEEK_MILLIS, ScheduleMath.periodMillis(SchedulePolicy(frequency = ScheduleFrequency.WEEKLY, daysOfWeek = setOf(5))))
        assertEquals(ScheduleMath.DAY_MILLIS, ScheduleMath.periodMillis(SchedulePolicy(frequency = ScheduleFrequency.WEEKLY, daysOfWeek = setOf(1, 5))))
        assertEquals(30 * ScheduleMath.MINUTE_MILLIS, ScheduleMath.periodMillis(SchedulePolicy(frequency = ScheduleFrequency.INTERVAL, intervalMinutes = 30)))
        assertEquals(ScheduleMath.HOUR_MILLIS, ScheduleMath.flexMillis(ScheduleMath.DAY_MILLIS))
        assertEquals(ScheduleMath.MIN_FLEX_MILLIS, ScheduleMath.flexMillis(15 * ScheduleMath.MINUTE_MILLIS))
    }

    // --- Spec / constraints -----------------------------------------------------------------------

    @Test
    fun networkRequirementFollowsDestinationType() {
        assertEquals(NetworkRequirement.NONE, ScheduleSpecs.networkFor(DestinationType.LOCAL_INTERNAL, requireUnmetered = true))
        assertEquals(NetworkRequirement.NONE, ScheduleSpecs.networkFor(DestinationType.USB_OTG, requireUnmetered = true))
        assertEquals(NetworkRequirement.NONE, ScheduleSpecs.networkFor(null, requireUnmetered = true))
        assertEquals(NetworkRequirement.UNMETERED, ScheduleSpecs.networkFor(DestinationType.SFTP, requireUnmetered = true))
        assertEquals(NetworkRequirement.CONNECTED, ScheduleSpecs.networkFor(DestinationType.RCLONE, requireUnmetered = false))
    }

    @Test
    fun periodicConstraintsMirrorPolicy() {
        val policy = SchedulePolicy(requireCharging = true, requireDeviceIdle = true, requireUnmetered = false)
        val c = ScheduleSpecs.periodicConstraints(policy, DestinationType.SMB)
        assertEquals(NetworkRequirement.CONNECTED, c.network)
        assertTrue(c.requiresCharging)
        assertTrue(c.requiresDeviceIdle)
        assertTrue(c.requiresBatteryNotLow)
        assertTrue(c.requiresStorageNotLow)
    }

    @Test
    fun immediateConstraintsOnlyNeedNetwork() {
        val c = ScheduleSpecs.immediateConstraints(DestinationType.WEBDAV)
        assertEquals(RunConstraints(NetworkRequirement.CONNECTED, false, false, false, false), c)
        assertEquals(NetworkRequirement.NONE, ScheduleSpecs.immediateConstraints(DestinationType.LOCAL_SAF).network)
    }

    @Test
    fun specAlignsNextRunAndInitialDelay() {
        val now = at(2026, 10, 19, 1, 0)
        val spec = ScheduleSpecs.build(profile(SchedulePolicy(hour = 2)), DestinationType.LOCAL_INTERNAL, now, zone, catchUp = false)!!
        assertEquals(at(2026, 10, 19, 2, 0), spec.nextRunAtMillis)
        assertEquals(ScheduleMath.DAY_MILLIS, spec.periodMillis)
        assertEquals(ScheduleMath.HOUR_MILLIS, spec.flexMillis)
        // 1 h ahead is less than period − flex (23 h): WorkManager's own formula cannot reach it, override does.
        assertEquals(0L, spec.initialDelayMillis)
        assertEquals(NetworkRequirement.NONE, spec.constraints.network)
    }

    @Test
    fun initialDelayMatchesWorkManagerFirstRunFormula() {
        val policy = SchedulePolicy(frequency = ScheduleFrequency.WEEKLY, hour = 2, daysOfWeek = setOf(7))
        val now = at(2026, 10, 18, 2, 30) // Sunday just after the slot; next one is 6 d 23.5 h away
        val spec = ScheduleSpecs.build(profile(policy), null, now, zone, catchUp = false)!!
        assertEquals(at(2026, 10, 25, 2, 0), spec.nextRunAtMillis)
        assertEquals(30 * ScheduleMath.MINUTE_MILLIS, spec.initialDelayMillis)
        assertEquals(spec.nextRunAtMillis, now + spec.initialDelayMillis + spec.periodMillis - spec.flexMillis)
    }

    @Test
    fun catchUpRunsOwedSlotNowOnlyWhenAllowed() {
        val now = at(2026, 10, 19, 9, 0)
        val missed = profile(SchedulePolicy(hour = 2, runOnBootIfMissed = true), lastRunAt = at(2026, 10, 18, 2, 0))
        assertEquals(now, ScheduleSpecs.build(missed, null, now, zone, catchUp = true)!!.nextRunAtMillis)
        assertEquals(at(2026, 10, 20, 2, 0), ScheduleSpecs.build(missed, null, now, zone, catchUp = false)!!.nextRunAtMillis)
        val noCatchUp = missed.copy(schedule = SchedulePolicy(hour = 2, runOnBootIfMissed = false))
        assertEquals(at(2026, 10, 20, 2, 0), ScheduleSpecs.build(noCatchUp, null, now, zone, catchUp = true)!!.nextRunAtMillis)
    }

    @Test
    fun noSpecForDisabledUnscheduledOrInvalid() {
        val now = at(2026, 10, 19, 9, 0)
        assertNull(ScheduleSpecs.build(profile(null), null, now, zone, catchUp = false))
        assertNull(ScheduleSpecs.build(profile(SchedulePolicy(), enabled = false), null, now, zone, catchUp = false))
        assertNull(ScheduleSpecs.build(profile(SchedulePolicy(frequency = ScheduleFrequency.WEEKLY)), null, now, zone, catchUp = false))
        assertNull(ScheduleSpecs.build(profile(SchedulePolicy(frequency = ScheduleFrequency.INTERVAL, intervalMinutes = 5)), null, now, zone, catchUp = false))
    }

    @Test
    fun fingerprintIgnoresLastRunButTracksSchedule() {
        val p = profile(SchedulePolicy(hour = 2))
        val type = DestinationType.SFTP
        assertEquals(ScheduleSpecs.fingerprint(p, type), ScheduleSpecs.fingerprint(p.copy(lastRunAt = 123L), type))
        assertFalse(ScheduleSpecs.fingerprint(p, type) == ScheduleSpecs.fingerprint(p.copy(schedule = SchedulePolicy(hour = 3)), type))
        assertFalse(ScheduleSpecs.fingerprint(p, type) == ScheduleSpecs.fingerprint(p, DestinationType.LOCAL_INTERNAL))
    }

    @Test
    fun workNamesAndTagsRoundTrip() {
        val tag = WorkRequestFactory.profileTag("abc-123")
        assertEquals("abc-123", WorkRequestFactory.profileIdFromTags(setOf("other", tag)))
        assertNull(WorkRequestFactory.profileIdFromTags(setOf("other")))
        assertFalse(WorkRequestFactory.periodicName("a") == WorkRequestFactory.oneTimeName("a", BackupWorker.TRIGGER_MANUAL))
        // Manual and resume runs never share a unique name (KEEP would drop one of them).
        assertFalse(
            WorkRequestFactory.oneTimeName("a", BackupWorker.TRIGGER_MANUAL) == WorkRequestFactory.oneTimeName("a", BackupWorker.TRIGGER_RESUME),
        )
        assertEquals(WorkRequestFactory.fingerprintTag("x|y"), WorkRequestFactory.fingerprintTag("x|y"))
        assertFalse(WorkRequestFactory.fingerprintTag("x|y") == WorkRequestFactory.fingerprintTag("x|z"))
    }
}
