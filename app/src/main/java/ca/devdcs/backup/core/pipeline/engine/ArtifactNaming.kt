package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.pipeline.checksum.ChecksumSidecar
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure naming rules for everything the engine writes at a destination. Exists so restore,
 * retention and the UI can rely on one predictable layout:
 * `<profileName>_<yyyyMMdd-HHmmss>/<artifact><ext>[.dftbenc]` plus sidecars and the manifest.
 */
object ArtifactNaming {

    const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss"
    const val ENCRYPTED_SUFFIX = ".dftbenc"
    const val PART_SUFFIX = ".part"
    const val MANIFEST_NAME = "dftb-manifest.json"
    const val ENCRYPTED_MANIFEST_NAME = MANIFEST_NAME + ENCRYPTED_SUFFIX
    const val MAX_SEGMENT_LENGTH = 96
    private const val FALLBACK_SEGMENT = "backup"

    private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN, Locale.ROOT)
    /** Upper bound accepted for a resumed folder name (common 255-byte filename limit). */
    const val MAX_RESUME_SEGMENT_LENGTH = 255
    private val SAFE_SEGMENT = Regex("[A-Za-z0-9-][A-Za-z0-9._-]*")
    private val UNSAFE = Regex("[^A-Za-z0-9._-]+")
    private val REPEATED = Regex("_{2,}")

    fun timestamp(epochMillis: Long, zone: ZoneId): String = TIMESTAMP.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /**
     * A filesystem- and URL-safe single path segment: only `[A-Za-z0-9._-]`, no leading dot (so it
     * is never hidden and never `.`/`..`), bounded length, never empty.
     */
    fun sanitizeSegment(raw: String, maxLength: Int = MAX_SEGMENT_LENGTH): String {
        val cleaned = raw.trim()
            .replace(UNSAFE, "_")
            .replace(REPEATED, "_")
            .trimStart('.', '_', '-')
            .trimEnd('_')
            .take(maxLength)
            .trimEnd('_')
        return cleaned.ifEmpty { FALLBACK_SEGMENT }
    }

    /**
     * `<profile>_<timestamp>`, at most [MAX_SEGMENT_LENGTH] chars: the profile part is capped so
     * the timestamp suffix always survives (and a resume can reuse the name verbatim).
     */
    fun jobDirName(profileName: String, startedAtMillis: Long, zone: ZoneId): String {
        val suffix = "_" + timestamp(startedAtMillis, zone)
        return sanitizeSegment(profileName, MAX_SEGMENT_LENGTH - suffix.length) + suffix
    }

    /** True when [name] is exactly what [sanitizeSegment] could produce (safe to reuse verbatim). */
    fun isSafeSegment(name: String): Boolean =
        name.length in 1..MAX_RESUME_SEGMENT_LENGTH && SAFE_SEGMENT.matches(name)

    /** Matches every manifest name a job folder may hold (first run and resumed runs). */
    val MANIFEST_FILE: Regex = Regex("""dftb-manifest(?:[._-][A-Za-z0-9._-]*)?\.json(?:\.dftbenc)?""")

    /** Unmasked artifact base name for a source of [kind] described by [displayName]. */
    fun artifactBaseName(kind: SourceKind, displayName: String): String {
        val core = sanitizeSegment(displayName.trim('/'))
        return when (kind) {
            SourceKind.PARTITION -> core
            SourceKind.APP -> "app_$core"
            SourceKind.FILES -> "files_$core"
            SourceKind.NANDROID -> "twrp_$core"
            SourceKind.OTHER -> core
        }
    }

    /** Disambiguates equal bases in order: `a`, `a-2`, `a-3`… (case-insensitive, for FAT/SAF). */
    fun uniqueNames(bases: List<String>): List<String> {
        val used = HashSet<String>()
        return bases.map { base ->
            var candidate = base
            var n = 2
            while (!used.add(candidate.lowercase(Locale.ROOT))) {
                candidate = "$base-$n"
                n++
            }
            candidate
        }
    }

    /** Extension of the stored file(s) for [spec] (for FILE_TREE, the per-file suffix). */
    fun extension(spec: ArtifactSpec): String = when (spec.layout) {
        ArtifactLayout.ARCHIVE -> spec.format.fileExtension
        ArtifactLayout.RAW_STREAM -> ArchiveFormat.DD_IMAGE.fileExtension + spec.codec.fileExtension
        ArtifactLayout.FILE_TREE -> spec.codec.fileExtension
    }

    /** Final unmasked name: the file for ARCHIVE/RAW_STREAM, the folder for FILE_TREE. */
    fun artifactName(base: String, spec: ArtifactSpec, encrypted: Boolean): String = when (spec.layout) {
        ArtifactLayout.FILE_TREE -> base
        else -> base + extension(spec) + if (encrypted) ENCRYPTED_SUFFIX else ""
    }

    /** Stored name of one file inside a FILE_TREE artifact, relative to the artifact folder. */
    fun treeFilePath(archivePath: String, spec: ArtifactSpec, encrypted: Boolean, mask: ((String) -> String)?): String {
        val segments = archivePath.split('/').filter { it.isNotEmpty() }
        require(segments.isNotEmpty() && segments.none { it == "." || it == ".." }) { "Invalid archive path: $archivePath" }
        val last = segments.last() + extension(spec) + if (encrypted) ENCRYPTED_SUFFIX else ""
        val all = segments.dropLast(1) + last
        return all.joinToString("/") { segment -> mask?.invoke(segment) ?: segment }
    }

    /** `<storedName>.<alg ext>`; TWRP style names SHA-256 sidecars `.sha2` like TWRP does. */
    fun checksumSidecarName(storedName: String, algorithm: ChecksumAlgorithm, twrpStyle: Boolean = false): String =
        ChecksumSidecar.sidecarName(
            storedName,
            algorithm,
            if (twrpStyle) ChecksumSidecar.Style.TWRP else ChecksumSidecar.Style.STANDARD,
        )

    fun partPath(path: String): String = path + PART_SUFFIX

    fun manifestName(encryptedManifest: Boolean): String = if (encryptedManifest) ENCRYPTED_MANIFEST_NAME else MANIFEST_NAME

    /**
     * Manifest of a resumed run written into a job folder that already has one:
     * `dftb-manifest.<epochMillis>.json[.dftbenc]`, so the restore catalog can merge every run.
     */
    fun resumedManifestName(epochMillis: Long, encryptedManifest: Boolean): String =
        "dftb-manifest.$epochMillis.json" + if (encryptedManifest) ENCRYPTED_SUFFIX else ""

    /** '/'-joins non-empty parts. */
    fun join(vararg parts: String): String = parts.filter { it.isNotEmpty() }.joinToString("/")
}
