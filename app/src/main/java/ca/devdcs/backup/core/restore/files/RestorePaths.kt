package ca.devdcs.backup.core.restore.files

import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout

/**
 * Pure mapping from archive paths back to where files came from. Exists so "restore to the
 * original location" is decided in tested code: DFTB stores files as `files/<root-label>/<rel>`
 * with labels derived from the profile's path targets ([ArchiveLayout.rootLabel], duplicates
 * numbered `-2`, `-3` in selection order), and TWRP folders under `TWRP/BACKUPS/...` relative to
 * the primary shared storage.
 */
object RestorePaths {

    /** Where an archive path came from: a scan root location and the path below it. */
    data class Origin(val rootLocation: String, val relativePath: String)

    /**
     * label → root location for [locations] (profile `targets.paths`, in order), mirroring the
     * backup's label allocation. Roots scanned only for categories have no stable label and are
     * absent, so their files cannot be mapped back.
     */
    fun labels(locations: List<String>): Map<String, String> {
        val used = HashSet<String>()
        val out = LinkedHashMap<String, String>()
        for (location in locations.distinct()) {
            val base = ArchiveLayout.rootLabel(location)
            var candidate = base
            var n = 2
            while (!used.add(candidate.lowercase())) candidate = "$base-${n++}"
            out[candidate] = location
        }
        return out
    }

    /** `files/<label>/<rel>` → origin; null when not a hierarchy file path or the label is unknown. */
    fun origin(archivePath: String, labels: Map<String, String>): Origin? {
        val segments = archivePath.split('/')
        if (segments.size < 2 || segments[0] != ArchiveLayout.FILES) return null
        val root = labels[segments[1]] ?: return null
        return Origin(root, segments.drop(2).joinToString("/"))
    }

    /** The label segment of a `files/<label>/...` path, or null. */
    fun labelOf(archivePath: String): String? {
        val segments = archivePath.split('/')
        return if (segments.size >= 2 && segments[0] == ArchiveLayout.FILES) segments[1] else null
    }

    /** Path inside a user-chosen folder: DFTB's `files/` prefix is dropped, the label kept. */
    fun inChosenFolder(archivePath: String): String =
        if (archivePath.startsWith(ArchiveLayout.FILES + "/")) archivePath.removePrefix(ArchiveLayout.FILES + "/") else archivePath

    /** Segment-wise prefix filter: `a/b` selects `a/b` and `a/b/c`, never `a/bc`. */
    fun selected(archivePath: String, include: Set<String>?): Boolean {
        if (include == null) return true
        return include.any { prefix ->
            val p = prefix.trim('/')
            p.isEmpty() || archivePath == p || archivePath.startsWith("$p/")
        }
    }

    /** `data.ext4.win001` → `data`: the folder a TWRP volume is extracted into. */
    fun twrpVolumeFolder(fileName: String): String = ArchiveLayout.segment(fileName.substringBefore('.'))
}
