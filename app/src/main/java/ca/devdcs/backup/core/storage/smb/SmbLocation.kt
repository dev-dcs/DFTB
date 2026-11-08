package ca.devdcs.backup.core.storage.smb

import ca.devdcs.backup.core.storage.ssh.RemotePaths

/**
 * Where on an SMB server a destination lives: the share to tree-connect plus the folder inside it.
 * Exists (pure, unit-tested) because users enter SMB locations several ways — `/Backups/phone`,
 * `Backups\phone`, or share in `options["share"]` with a plain folder path — and every form must
 * resolve to the same share-relative, backslash-separated paths smbj expects.
 */
internal data class SmbLocation(
    val share: String,
    /** Folder segments inside [share] that contract paths are relative to. */
    val baseSegments: List<String>,
) {
    /** Share-relative SMB path (backslash separated, `""` = share root) for a contract path. */
    fun smbPath(relative: String): String =
        (baseSegments + RemotePaths.segments(relative.replace('\\', '/'))).joinToString("\\")

    companion object {
        /** Returns null when no share can be determined. */
        fun parse(basePath: String?, shareOption: String?): SmbLocation? {
            val segments = RemotePaths.segments(basePath.orEmpty().replace('\\', '/'))
            val explicitShare = shareOption?.trim()?.trim('/', '\\')?.takeIf { it.isNotEmpty() }
            if (explicitShare != null) return SmbLocation(explicitShare, segments)
            val share = segments.firstOrNull() ?: return null
            return SmbLocation(share, segments.drop(1))
        }
    }
}
