package ca.devdcs.backup.domain.model

import kotlinx.serialization.Serializable

/**
 * How many past artifacts of a profile to keep. Both limits are optional and combine with OR: an
 * artifact is pruned when it is beyond [keepLast] *or* older than [maxAgeDays]. Null for both
 * means keep everything forever.
 */
@Serializable
data class RetentionPolicy(
    val keepLast: Int? = null,
    val maxAgeDays: Int? = null,
) {
    val isUnlimited: Boolean get() = keepLast == null && maxAgeDays == null

    /** Epoch-millis cutoff before which artifacts expire, or null when there is no age limit. */
    fun cutoffMillis(nowEpochMillis: Long): Long? = maxAgeDays?.let { days -> nowEpochMillis - days * MILLIS_PER_DAY }

    companion object {
        const val MILLIS_PER_DAY = 86_400_000L
        val KEEP_ALL = RetentionPolicy()
    }
}
