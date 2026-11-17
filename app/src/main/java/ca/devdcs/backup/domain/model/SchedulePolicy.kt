package ca.devdcs.backup.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ScheduleFrequency {
    @SerialName("daily") DAILY,
    @SerialName("weekly") WEEKLY,
    @SerialName("interval") INTERVAL,
}

/**
 * When and under which device conditions a profile runs unattended. Stored as JSON on the profile;
 * the scheduler translates it into WorkManager constraints and an initial delay. Days use ISO
 * numbering (1 = Monday .. 7 = Sunday) so they map 1:1 onto java.time.DayOfWeek.
 */
@Serializable
data class SchedulePolicy(
    val frequency: ScheduleFrequency = ScheduleFrequency.DAILY,
    val hour: Int = 2,
    val minute: Int = 0,
    val daysOfWeek: Set<Int> = emptySet(),
    val intervalMinutes: Int? = null,
    val requireUnmetered: Boolean = true,
    val requireCharging: Boolean = false,
    val minBatteryPercent: Int? = null,
    val requireDeviceIdle: Boolean = false,
    val runOnBootIfMissed: Boolean = true,
) {
    /**
     * Null when the policy is executable, otherwise a short machine-readable reason the editor
     * maps to a localized message. Kept out of `init` so a stale row can still be loaded and fixed.
     */
    val problem: Problem?
        get() = when {
            hour !in 0..23 || minute !in 0..59 -> Problem.TIME_OUT_OF_RANGE
            frequency == ScheduleFrequency.WEEKLY && daysOfWeek.isEmpty() -> Problem.NO_DAYS_SELECTED
            daysOfWeek.any { it !in 1..7 } -> Problem.INVALID_DAY
            frequency == ScheduleFrequency.INTERVAL && (intervalMinutes == null || intervalMinutes < MIN_INTERVAL_MINUTES) ->
                Problem.INTERVAL_TOO_SHORT
            minBatteryPercent != null && minBatteryPercent !in 0..100 -> Problem.BATTERY_OUT_OF_RANGE
            else -> null
        }

    val isValid: Boolean get() = problem == null

    enum class Problem { TIME_OUT_OF_RANGE, NO_DAYS_SELECTED, INVALID_DAY, INTERVAL_TOO_SHORT, BATTERY_OUT_OF_RANGE }

    companion object {
        /** WorkManager's own floor for periodic work. */
        const val MIN_INTERVAL_MINUTES = 15
    }
}
