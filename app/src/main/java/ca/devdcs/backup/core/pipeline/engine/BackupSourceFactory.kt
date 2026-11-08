package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.pipeline.BackupSource
import ca.devdcs.backup.domain.model.BackupProfile
import ca.devdcs.backup.domain.model.TargetSelection

/**
 * Broad family of a [BackupSource]. The engine needs it to pick the artifact shape: a partition
 * becomes a raw (optionally compressed) image, apps and file roots become archives, a TWRP
 * (Nandroid) folder is stored verbatim.
 */
enum class SourceKind(val idPrefix: String) {
    PARTITION("partition:"),
    APP("app:"),
    FILES("files:"),
    NANDROID("nandroid:"),
    OTHER("");

    companion object {
        /** Kind by a `<kind>:<rest>` id convention; ids without a known prefix are [OTHER]. */
        fun fromSourceId(sourceId: String): SourceKind =
            entries.firstOrNull { it != OTHER && sourceId.startsWith(it.idPrefix) } ?: OTHER
    }
}

/**
 * One source ready to run, i.e. one output artifact. [displayName] is the human base of the
 * artifact name (partition, package, root). [storeVerbatim] means the produced items must reach
 * the destination byte-for-byte (TWRP folders: no compression, encryption or name masking).
 */
data class ResolvedSource(
    val source: BackupSource,
    val kind: SourceKind,
    val displayName: String,
    val storeVerbatim: Boolean = false,
    /**
     * Issues only known after the source's items flow completed (e.g. files that vanished while a
     * TWRP data volume was read). The engine reads it once per artifact, after producing it.
     */
    val runtimeIssues: () -> List<SourceIssue> = { emptyList() },
)

/**
 * Something a source left out or could not do in this environment. [blocking] = the whole source
 * was dropped (e.g. partitions without root); otherwise it runs with reduced coverage. [message]
 * is localized; [detail] is technical.
 */
data class SourceIssue(val subject: String, val message: String, val detail: String? = null, val blocking: Boolean)

/** Everything a profile's targets resolved to. */
data class SourceResolution(val sources: List<ResolvedSource>, val issues: List<SourceIssue> = emptyList())

/**
 * Turns a profile's [TargetSelection] into concrete sources, one per output artifact (one per
 * partition, one per app, one per file root, or one TWRP folder). Exists so the engine depends
 * only on this seam and never on the concrete partition/app/file source classes. Environment
 * limits are reported as [SourceIssue]s, never thrown.
 */
interface BackupSourceFactory {
    suspend fun create(profile: BackupProfile, selection: TargetSelection): SourceResolution
}
