package ca.devdcs.backup.core.storage.nfs

import ca.devdcs.backup.core.storage.ssh.PosixShell
import ca.devdcs.backup.core.storage.ssh.RemotePaths

/**
 * Pure planning for an NFS mount through the root shell: export/sub-path resolution, mount option
 * merging, kernel support detection and mount-table parsing. Exists separately from the backend so
 * every string that ends up in a root `mount` command is built and validated by tested code.
 */
internal object NfsMountPlanner {

    /** Conservative defaults: v3 (no idmapd/Kerberos needed), no lockd, TCP, soft with 60 s timeouts. */
    const val DEFAULT_OPTIONS = "vers=3,nolock,proto=tcp,soft,timeo=600"
    const val MOUNT_ROOT = "/data/local/tmp/dftb_mnt"

    /**
     * Always applied last: the share is untrusted input to a root process, so set-uid binaries,
     * device nodes and executables on it must be inert.
     */
    const val ENFORCED_OPTIONS = "nosuid,nodev,noexec"

    /** Kernel 5.10+ flag refusing symlink traversal on the mount; dropped if the kernel rejects it. */
    const val NOSYMFOLLOW = "nosymfollow"

    private val SECURITY_KEYS = setOf("suid", "dev", "exec", "symfollow")

    private val SAFE_OPTIONS = Regex("^[A-Za-z0-9_.:=,/+\\-\\[\\]]*$")

    /** The export to mount and the folder inside it that contract paths are relative to. */
    data class Target(val export: String, val subPath: String)

    /**
     * `options["export"]` names the export and `basePath` is a folder inside it; otherwise
     * `basePath` itself is the export. Null when no absolute export path is configured.
     */
    fun resolveTarget(basePath: String?, exportOption: String?): Target? {
        val explicit = exportOption?.trim()?.takeIf { it.isNotEmpty() }
        return if (explicit != null) {
            if (!explicit.startsWith("/")) null else Target(normalizeExport(explicit), RemotePaths.normalize(basePath.orEmpty()))
        } else {
            val base = basePath?.trim()?.takeIf { it.startsWith("/") } ?: return null
            Target(normalizeExport(base), "")
        }
    }

    private fun normalizeExport(path: String): String = "/" + RemotePaths.segments(path).joinToString("/")

    /** Per-destination mountpoint; the id is reduced to a filename-safe token. */
    fun mountPoint(destinationId: String): String {
        val safe = destinationId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return "$MOUNT_ROOT/" + safe.ifEmpty { "default" }.take(MAX_ID_LENGTH)
    }

    fun optionsAreSafe(options: String): Boolean = SAFE_OPTIONS.matches(options)

    /** True when user [options] try to re-enable suid/dev/exec/symlink following (rejected). */
    fun weakensSecurity(options: String): Boolean =
        options.split(',').map { it.trim().substringBefore('=') }.any { it in SECURITY_KEYS }

    /**
     * Merge [overrides] onto [defaults] by key (`key=value` or bare flag), resolving the
     * soft/hard and lock/nolock pairs, then force [ENFORCED_OPTIONS] (+ [NOSYMFOLLOW] when
     * [noSymFollow]) and add `addr=<ip>` (the kernel's NFS client does not resolve host names
     * itself) unless the user set it.
     */
    fun mergeOptions(defaults: String, overrides: String?, address: String, noSymFollow: Boolean): String {
        val merged = LinkedHashMap<String, String?>()
        fun apply(list: String?) {
            list?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { option ->
                val key = option.substringBefore('=')
                val value = if ('=' in option) option.substringAfter('=') else null
                OPPOSITES[key]?.let { merged.remove(it) }
                merged[key] = value
            }
        }
        apply(defaults)
        apply(overrides)
        apply(ENFORCED_OPTIONS)
        if (noSymFollow) apply(NOSYMFOLLOW)
        if ("addr" !in merged) merged["addr"] = address
        return merged.entries.joinToString(",") { (k, v) -> if (v == null) k else "$k=$v" }
    }

    /** `host:/export` device string; IPv6 literals are bracketed. */
    fun source(address: String, export: String): String =
        if (':' in address) "[$address]:$export" else "$address:$export"

    /** Filesystem names from `/proc/filesystems` (`nodev\tnfs` -> `nfs`). */
    fun parseFilesystems(lines: List<String>): Set<String> =
        lines.mapNotNull { line -> line.trim().split(Regex("\\s+")).lastOrNull()?.takeIf { it.isNotEmpty() } }.toSet()

    /** The `-t` type to mount with, or null when the kernel lacks an NFS client for [options]. */
    fun chooseFsType(filesystems: Set<String>, options: String): String? {
        val wantsV4 = options.split(',').any { it.trim().let { o -> o.startsWith("vers=4") || o.startsWith("nfsvers=4") } }
        return when {
            wantsV4 && "nfs4" in filesystems -> "nfs4"
            "nfs" in filesystems -> "nfs"
            else -> null
        }
    }

    /** Whether [mountPoint] appears as a mount target in `/proc/mounts` lines. */
    fun isMounted(procMounts: List<String>, mountPoint: String): Boolean = procMounts.any { line ->
        val fields = line.trim().split(Regex("\\s+"))
        fields.size >= 2 && fields[1].replace("\\040", " ") == mountPoint
    }

    /** `mkdir -p` the mountpoint and mount. Every argument is shell-quoted. */
    fun mountScript(fsType: String, options: String, source: String, mountPoint: String): String {
        val mp = PosixShell.quote(mountPoint)
        return "mkdir -p $mp && mount -t ${PosixShell.quote(fsType)} -o ${PosixShell.quote(options)} " +
            "${PosixShell.quote(source)} $mp"
    }

    /** Lazy unmount (survives a busy handle) and remove the empty mountpoint; never fails. */
    fun unmountScript(mountPoint: String): String {
        val mp = PosixShell.quote(mountPoint)
        return "umount -l $mp 2>/dev/null; rmdir $mp 2>/dev/null; true"
    }

    private val OPPOSITES = mapOf(
        "soft" to "hard",
        "hard" to "soft",
        "lock" to "nolock",
        "nolock" to "lock",
        "ac" to "noac",
        "noac" to "ac",
        "suid" to "nosuid",
        "nosuid" to "suid",
        "dev" to "nodev",
        "nodev" to "dev",
        "exec" to "noexec",
        "noexec" to "exec",
        "symfollow" to "nosymfollow",
        "nosymfollow" to "symfollow",
    )

    private const val MAX_ID_LENGTH = 64
}
