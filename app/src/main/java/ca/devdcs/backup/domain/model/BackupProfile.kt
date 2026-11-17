package ca.devdcs.backup.domain.model

/**
 * A saved, repeatable backup definition: what ([targets]), how ([pipeline]), where
 * ([destinationId]) and when ([schedule]). Ids are caller-generated UUID strings so a profile can
 * be created offline, exported and re-imported without colliding.
 */
data class BackupProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val targets: TargetSelection = TargetSelection.EMPTY,
    val destinationId: String? = null,
    val pipeline: PipelineOptions = PipelineOptions.DEFAULT,
    val schedule: SchedulePolicy? = null,
    val retention: RetentionPolicy = RetentionPolicy.KEEP_ALL,
    val createdAt: Long,
    val updatedAt: Long,
    val lastRunAt: Long? = null,
    val enabled: Boolean = true,
) {
    /** True when the scheduler should own this profile. */
    val isScheduled: Boolean get() = enabled && schedule != null
}
