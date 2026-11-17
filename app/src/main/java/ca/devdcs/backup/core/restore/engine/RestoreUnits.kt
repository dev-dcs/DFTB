package ca.devdcs.backup.core.restore.engine

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.SourceKind
import ca.devdcs.backup.core.restore.ConflictPolicy
import ca.devdcs.backup.core.restore.OpenedBackup
import ca.devdcs.backup.core.restore.RestoreUnitResult
import ca.devdcs.backup.core.restore.apps.AppRestoreOptions
import ca.devdcs.backup.core.restore.partition.PartitionRestoreOptions

/**
 * What kind of restore unit an artifact belongs to. Exists so the engine can route each artifact
 * to the restorer that understands it without parsing source ids in several places.
 */
enum class RestoreUnitKind {
    /** FILES / NANDROID / OTHER artifacts: extracted by the engine's own file restorer. */
    FILE_TREE,

    /** `app:` artifacts (APK split set + data archives), handled by an [AppRestorer]. */
    APP,

    /** `partition:` dd images, handled by a [PartitionRestorer]. */
    PARTITION;

    companion object {
        /**
         * Routes on the manifest `kind` (BackupJob writes `SourceKind.name` lowercased); source ids
         * are only a fallback because real sources use `apps:` / `partitions:` prefixes that differ
         * from [SourceKind.idPrefix].
         */
        fun of(artifact: ManifestArtifact): RestoreUnitKind {
            val byKind = SourceKind.entries.firstOrNull { it.name.equals(artifact.kind, ignoreCase = true) }
            val kind = byKind ?: when {
                artifact.sourceId.startsWith("apps:") || artifact.sourceId.startsWith(SourceKind.APP.idPrefix) -> SourceKind.APP
                artifact.sourceId.startsWith("partitions:") || artifact.sourceId.startsWith(SourceKind.PARTITION.idPrefix) -> SourceKind.PARTITION
                else -> SourceKind.fromSourceId(artifact.sourceId)
            }
            return when (kind) {
                SourceKind.APP -> APP
                SourceKind.PARTITION -> PARTITION
                SourceKind.FILES, SourceKind.NANDROID, SourceKind.OTHER -> FILE_TREE
            }
        }

        /** Manifest kind check shared by the catalog and file restorer. */
        fun isKind(artifact: ManifestArtifact, kind: SourceKind): Boolean =
            artifact.kind.equals(kind.name, ignoreCase = true) || SourceKind.fromSourceId(artifact.sourceId) == kind
    }
}

/**
 * Per-run restore knobs shared by every unit. Exists so app, partition and file restorers agree
 * on conflict handling, metadata restoration and safety overrides without each defining its own.
 */
data class RestoreOptions(
    val conflictPolicy: ConflictPolicy = ConflictPolicy.SKIP,
    /** Restore mtime always, and mode/uid/gid/SELinux label when the tier allows it. */
    val restoreMetadata: Boolean = true,
    /** Proceed although the target reports less free space than required. */
    val ignoreInsufficientSpace: Boolean = false,
    /** Allow writing images / app data backed up on a different device model or slot. */
    val allowCrossDevice: Boolean = false,
    /** Verify stored digests of every artifact before writing anything (adds one full read). */
    val verifyBeforeRestore: Boolean = false,
    /** App units: restore APKs. */
    val restoreApks: Boolean = true,
    /** App units: restore private / external data. */
    val restoreAppData: Boolean = true,
    /** Partition units: explicit per-gate overrides; null = derived via [PartitionRestoreOptions.from]. */
    val partition: PartitionRestoreOptions? = null,
    /** App units: user, version, signature and safety choices; null = [AppRestoreOptions] defaults. */
    val app: AppRestoreOptions? = null,
)

/**
 * Progress callbacks a unit restorer reports through. Exists so restorers written by other
 * modules feed the same job-level progress, cancellation and warning stream as file restores.
 */
interface RestoreProgress {
    /** Count [delta] more plaintext bytes written to the target. */
    fun onBytes(delta: Long)

    /** A new item (file, APK, partition) started; [name] is user-facing. */
    fun onItem(name: String)

    /** A non-fatal condition; [message] is user-facing (already localized). */
    fun onWarning(message: String)

    /** True once the user asked to cancel; restorers stop at the next safe point and throw CancellationException. */
    fun isCancelled(): Boolean
}

/**
 * Result of checking whether a unit can be restored here, before anything is written. Exists so
 * preflight can report every blocked unit at once instead of failing midway through a restore.
 */
data class UnitPreflight(
    val unitId: String,
    val available: Boolean,
    /** Tier needed to restore this unit (NONE = unprivileged is enough). */
    val requiredTier: ElevationTier,
    /** Bytes the unit will write on the target filesystem, or null when unknown. */
    val requiredBytes: Long? = null,
    /** Free bytes on the unit's target, or null when unknown. */
    val freeBytes: Long? = null,
    /** User-facing reason when [available] is false. */
    val reason: String? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * Restores app units (APKs + data) from an opened backup. Implemented outside this package; the
 * engine only orchestrates. [artifacts] are all artifacts of one app unit (same sourceId).
 * Must not throw for environment limits (no root, missing tool): return a failed result instead.
 * Must rethrow CancellationException.
 */
interface AppRestorer {
    suspend fun preflight(backup: OpenedBackup, artifacts: List<ManifestArtifact>, options: RestoreOptions): UnitPreflight

    suspend fun restore(
        backup: OpenedBackup,
        artifacts: List<ManifestArtifact>,
        options: RestoreOptions,
        progress: RestoreProgress,
    ): RestoreUnitResult
}

/**
 * Writes partition images back. Implemented outside this package (needs root, device/slot checks).
 * Same error and cancellation rules as [AppRestorer].
 */
interface PartitionRestorer {
    suspend fun preflight(backup: OpenedBackup, artifacts: List<ManifestArtifact>, options: RestoreOptions): UnitPreflight

    suspend fun restore(
        backup: OpenedBackup,
        artifacts: List<ManifestArtifact>,
        options: RestoreOptions,
        progress: RestoreProgress,
    ): RestoreUnitResult
}
