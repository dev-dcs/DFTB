package ca.devdcs.backup.core.restore.catalog

import ca.devdcs.backup.core.pipeline.engine.ArtifactNaming
import ca.devdcs.backup.core.pipeline.engine.BackupManifest
import ca.devdcs.backup.core.pipeline.engine.ManifestArtifact
import ca.devdcs.backup.core.pipeline.engine.SourceKind
import java.util.Locale

/** One manifest file found in a job folder. */
data class ManifestRun(val path: String, val encrypted: Boolean, val manifest: BackupManifest)

/**
 * Merges the manifests of one job folder. A resumed backup run writes a manifest describing only
 * the artifacts that run produced, so the full backup is the union of every run's manifest. Pure,
 * so the "latest run wins per artifact" rule and the consistency checks are unit-tested.
 */
object ManifestMerger {

    private val MANIFEST_NAME = Regex(
        "^" + Regex.escape("dftb-manifest") + "(?:[._-][A-Za-z0-9._-]*)?" + Regex.escape(".json") +
            "(?:" + Regex.escape(ArtifactNaming.ENCRYPTED_SUFFIX) + ")?$",
    )

    /** `dftb-manifest.json`, `dftb-manifest.<run>.json`, … with optional `.dftbenc`; never a `.part`. */
    fun isManifestName(name: String): Boolean =
        !name.endsWith(ArtifactNaming.PART_SUFFIX) && MANIFEST_NAME.matches(name)

    fun isEncryptedName(name: String): Boolean = name.endsWith(ArtifactNaming.ENCRYPTED_SUFFIX)

    /** Oldest first: finish time, then start time, then path (deterministic for equal clocks). */
    fun chronological(runs: List<ManifestRun>): List<ManifestRun> =
        runs.sortedWith(compareBy<ManifestRun>({ it.manifest.finishedAtMillis }, { it.manifest.createdAtMillis }, { it.path }))

    /**
     * Union of [runs] keyed by artifact sourceId (the most recent run wins), in first-seen order.
     * @throws CatalogException INCONSISTENT_RUNS when runs disagree on profile, manifest
     * encryption, masking parameters or artifact encryption; NO_MANIFEST when [runs] is empty.
     */
    fun merge(runs: List<ManifestRun>): BackupManifest {
        if (runs.isEmpty()) throw CatalogException(CatalogException.Reason.NO_MANIFEST, "No manifest to merge")
        val ordered = chronological(runs)
        if (ordered.size == 1) {
            checkArtifactEncryption(ordered[0].manifest.artifacts)
            return ordered[0].manifest
        }
        val first = ordered.first()
        for (run in ordered.drop(1)) {
            if (run.manifest.profile.id != first.manifest.profile.id) {
                inconsistent("${run.path} belongs to profile ${run.manifest.profile.id}, ${first.path} to ${first.manifest.profile.id}")
            }
            if (run.encrypted != first.encrypted) inconsistent("${run.path} and ${first.path} differ in manifest encryption")
            if (run.manifest.masking != first.manifest.masking) inconsistent("${run.path} and ${first.path} use different filename masking")
        }
        val merged = LinkedHashMap<String, ManifestArtifact>()
        for (run in ordered) {
            for (artifact in run.manifest.artifacts) {
                // Re-inserting keeps the first-seen position while the newer value replaces the old.
                merged[artifact.sourceId] = artifact
            }
        }
        val artifacts = merged.values.toList()
        checkArtifactEncryption(artifacts)
        val latest = ordered.last().manifest
        // `partial` comes from the newest run: an older interrupted run completed by a resume
        // does not make the merged backup partial. Artifacts carried forward by several runs
        // collapse to one entry (newest wins) without being treated as a conflict.
        return latest.copy(
            createdAtMillis = ordered.minOf { it.manifest.createdAtMillis },
            finishedAtMillis = ordered.maxOf { it.manifest.finishedAtMillis },
            artifacts = artifacts,
            warnings = ordered.flatMap { it.manifest.warnings }.distinct(),
        )
    }

    /**
     * Every encrypted-capable artifact must agree: all encrypted with one scheme, or none. TWRP
     * (verbatim) artifacts are never encrypted and trees without files carry no header.
     */
    private fun checkArtifactEncryption(artifacts: List<ManifestArtifact>) {
        val schemes = artifacts
            .filter { participatesInEncryption(it) }
            .map { it.encryption?.scheme }
            .toSet()
        if (schemes.size > 1) {
            inconsistent("Artifacts disagree on encryption: ${schemes.joinToString { it ?: "none" }}")
        }
    }

    private fun participatesInEncryption(artifact: ManifestArtifact): Boolean {
        if (artifact.kind.equals(SourceKind.NANDROID.name, ignoreCase = true)) return false
        if (SourceKind.fromSourceId(artifact.sourceId) == SourceKind.NANDROID) return false
        if (artifact.layout.lowercase(Locale.ROOT) == "file_tree") return artifact.items.any { it.storedPath != null }
        return true
    }

    private fun inconsistent(detail: String): Nothing = throw CatalogException(CatalogException.Reason.INCONSISTENT_RUNS, detail)
}
