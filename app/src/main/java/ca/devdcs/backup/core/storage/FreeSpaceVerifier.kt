package ca.devdcs.backup.core.storage

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.repository.DestinationRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/** Outcome of a free-space preflight before a backup writes to a destination. */
sealed interface PreflightResult {
    /** Enough room for the payload plus the safety margin. */
    data class Sufficient(val space: SpaceInfo) : PreflightResult

    /** Not enough room; [shortfallBytes] is how much must be freed (margin included). */
    data class Insufficient(val space: SpaceInfo, val shortfallBytes: Long) : PreflightResult

    /**
     * Capacity could not be determined (protocol does not report it, or the destination was
     * unreachable). [failure] is set when a [StorageException] caused it.
     */
    data class Unknown(val reason: String, val failure: StorageException.Reason? = null) : PreflightResult
}

/**
 * Checks a destination has room for a backup before any byte is written.
 *
 * Exists so a multi-gigabyte job fails in seconds with an actionable message instead of hours in
 * with a half-written archive. The margin covers archive/container overhead and filesystem
 * slack. Observed capacity is persisted via [DestinationRepository.recordVerification] so the
 * dashboard shows fresh numbers.
 */
@Singleton
class FreeSpaceVerifier internal constructor(
    private val registry: StorageRegistry,
    private val repository: DestinationRepository,
    private val clock: () -> Long,
) {

    @Inject
    constructor(registry: StorageRegistry, repository: DestinationRepository) :
        this(registry, repository, System::currentTimeMillis)

    suspend fun verify(
        destination: Destination,
        requiredBytes: Long,
        safetyMarginFraction: Double = DEFAULT_MARGIN_FRACTION,
        minMarginBytes: Long = DEFAULT_MIN_MARGIN_BYTES,
    ): PreflightResult {
        val space = try {
            registry.withSession(destination) { it.spaceInfo() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StorageException) {
            DftbLog.w(TAG, "Free-space check failed for ${destination.id}: ${e.reason}")
            return PreflightResult.Unknown(e.message ?: e.reason.name, e.reason)
        }
        if (space != null && (space.freeBytes != null || space.totalBytes != null)) {
            try {
                repository.recordVerification(destination.id, clock(), space.freeBytes, space.totalBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Could not record capacity for ${destination.id}", e)
            }
        }
        return evaluate(space, requiredBytes, safetyMarginFraction, minMarginBytes)
    }

    companion object {
        private const val TAG = "FreeSpaceVerifier"
        const val DEFAULT_MARGIN_FRACTION: Double = 0.05
        const val DEFAULT_MIN_MARGIN_BYTES: Long = 64L * 1024 * 1024

        /**
         * Bytes needed including margin: `required + max(ceil(required * fraction), minMargin)`,
         * saturating at [Long.MAX_VALUE].
         */
        fun requiredWithMargin(requiredBytes: Long, safetyMarginFraction: Double, minMarginBytes: Long): Long {
            require(requiredBytes >= 0L) { "requiredBytes must be >= 0" }
            require(safetyMarginFraction >= 0.0 && !safetyMarginFraction.isNaN()) { "safetyMarginFraction must be >= 0" }
            require(minMarginBytes >= 0L) { "minMarginBytes must be >= 0" }
            val proportional = ceil(requiredBytes.toDouble() * safetyMarginFraction)
            val proportionalBytes = if (proportional >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else proportional.toLong()
            val margin = maxOf(proportionalBytes, minMarginBytes)
            return if (requiredBytes > Long.MAX_VALUE - margin) Long.MAX_VALUE else requiredBytes + margin
        }

        /** Pure decision given observed [space]; null or free-unknown space yields [PreflightResult.Unknown]. */
        fun evaluate(
            space: SpaceInfo?,
            requiredBytes: Long,
            safetyMarginFraction: Double = DEFAULT_MARGIN_FRACTION,
            minMarginBytes: Long = DEFAULT_MIN_MARGIN_BYTES,
        ): PreflightResult {
            val free = space?.freeBytes
                ?: return PreflightResult.Unknown("The destination does not report free space")
            val needed = requiredWithMargin(requiredBytes, safetyMarginFraction, minMarginBytes)
            return if (free >= needed) {
                PreflightResult.Sufficient(space)
            } else {
                PreflightResult.Insufficient(space, shortfallBytes = needed - free.coerceAtLeast(0L))
            }
        }
    }
}
