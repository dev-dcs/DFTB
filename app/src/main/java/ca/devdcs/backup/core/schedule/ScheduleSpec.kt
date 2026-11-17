package ca.devdcs.backup.core.schedule

import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.DestinationType
import ca.devdcs.backup.domain.model.SchedulePolicy
import java.time.ZoneId

/** Network a run needs, decided by the destination type (local destinations need none). */
enum class NetworkRequirement { NONE, CONNECTED, UNMETERED }

/** Device conditions for a run, independent of WorkManager types so they are unit-testable. */
data class RunConstraints(
    val network: NetworkRequirement,
    val requiresCharging: Boolean,
    val requiresBatteryNotLow: Boolean,
    val requiresStorageNotLow: Boolean,
    val requiresDeviceIdle: Boolean,
)

/**
 * Everything the scheduler hands WorkManager for one profile's periodic work. Exists as a pure
 * value (built by [ScheduleSpecs]) so the SchedulePolicy → WorkRequest translation is tested on
 * the JVM; [WorkRequestFactory] only copies these numbers into builders.
 */
data class ScheduleSpec(
    val profileId: String,
    val periodMillis: Long,
    val flexMillis: Long,
    /** Exact wall-clock time of the next run (WorkManager next-schedule-time override). */
    val nextRunAtMillis: Long,
    /**
     * Initial delay that makes WorkManager's own first-run formula (enqueue + delay + period − flex)
     * land on [nextRunAtMillis] where possible; only matters if the override were ignored.
     */
    val initialDelayMillis: Long,
    val constraints: RunConstraints,
    /** Checked by the worker at start; WorkManager only knows "battery not low". */
    val minBatteryPercent: Int?,
    /** Identity of the inputs, so the profile observer re-enqueues only on real changes. */
    val fingerprint: String,
)

/** Pure construction of [ScheduleSpec]s and one-off [RunConstraints]. */
object ScheduleSpecs {

    fun networkFor(destinationType: DestinationType?, requireUnmetered: Boolean): NetworkRequirement = when {
        destinationType == null || !destinationType.isNetwork -> NetworkRequirement.NONE
        requireUnmetered -> NetworkRequirement.UNMETERED
        else -> NetworkRequirement.CONNECTED
    }

    fun periodicConstraints(policy: SchedulePolicy, destinationType: DestinationType?): RunConstraints = RunConstraints(
        network = networkFor(destinationType, policy.requireUnmetered),
        requiresCharging = policy.requireCharging,
        requiresBatteryNotLow = true,
        // Local destinations and LOCAL_STAGING both write to device storage.
        requiresStorageNotLow = true,
        requiresDeviceIdle = policy.requireDeviceIdle,
    )

    /**
     * Constraints of an expedited one-off run ("Back up now", resume). Expedited work accepts only
     * network and storage constraints; the user asked for it now, so only the network is required
     * (any network: the user is present and chose to run).
     */
    fun immediateConstraints(destinationType: DestinationType?): RunConstraints = RunConstraints(
        network = networkFor(destinationType, requireUnmetered = false),
        requiresCharging = false,
        requiresBatteryNotLow = false,
        requiresStorageNotLow = false,
        requiresDeviceIdle = false,
    )

    /**
     * Spec for [profile], or null when it must not be scheduled (disabled, no schedule, invalid
     * schedule). [catchUp] makes an owed slot run as soon as constraints allow (boot / process
     * start with `runOnBootIfMissed`); otherwise the next future slot is used.
     */
    fun build(
        profile: BackupProfile,
        destinationType: DestinationType?,
        nowMillis: Long,
        zone: ZoneId,
        catchUp: Boolean,
    ): ScheduleSpec? {
        val policy = profile.schedule ?: return null
        if (!profile.enabled || !policy.isValid) return null
        val period = ScheduleMath.periodMillis(policy)
        val flex = ScheduleMath.flexMillis(period)
        val owed = catchUp && policy.runOnBootIfMissed && ScheduleMath.isDue(policy, nowMillis, zone, profile.lastRunAt)
        val next = if (owed) nowMillis else ScheduleMath.nextSlot(policy, nowMillis, zone)
        val delay = (next - nowMillis - (period - flex)).coerceAtLeast(0L)
        val constraints = periodicConstraints(policy, destinationType)
        return ScheduleSpec(
            profileId = profile.id,
            periodMillis = period,
            flexMillis = flex,
            nextRunAtMillis = next,
            initialDelayMillis = delay,
            constraints = constraints,
            minBatteryPercent = policy.minBatteryPercent,
            fingerprint = fingerprint(profile, destinationType),
        )
    }

    /** Changes only when something that affects the WorkRequest changes (not lastRunAt). */
    fun fingerprint(profile: BackupProfile, destinationType: DestinationType?): String =
        listOf(profile.enabled, profile.schedule, profile.destinationId, destinationType?.storageKey).joinToString("|")
}
