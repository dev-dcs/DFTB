package ca.devdcs.backup.core.pipeline.source

import ca.devdcs.backup.core.pipeline.source.PrivilegedStreamScripts.q

/**
 * Pure builders/parsers for app-data shell work. toybox facts this relies on (verified against the
 * AOSP toybox sources of android-10 .. 16): `tar -c -f - -C DIR --numeric-owner --no-recursion
 * --exclude=PAT -T FILE` exist on Android 10+ (`--exclude` matches the member name and, on 10, its
 * leading directories); `--selinux` (pax `RHT.security.selinux`) only exists from Android 13, so it
 * is probed at run time and the labels are always captured separately via `stat -c %C`.
 */
internal object AppDataScripts {

    /** Subdirs dropped from data tars unless CACHE is selected. */
    val CACHE_DIRS = listOf("cache", "code_cache")

    /** Prints `yes` when this toybox tar supports `--selinux`. */
    const val TAR_SELINUX_PROBE = "if tar --selinux -cf /dev/null /system/build.prop >/dev/null 2>&1; then echo yes; else echo no; fi"

    /** Prints each of [paths] that exists as a directory, one per line. */
    fun existingDirsScript(paths: List<String>): String {
        if (paths.isEmpty()) return "true"
        return "for __p in ${paths.joinToString(" ") { q(it) }}; do if [ -d \"\$__p\" ]; then printf '%s\\n' \"\$__p\"; fi; done; true"
    }

    /**
     * Tar [members] (names relative to [parent]) to stdout. [excludes] are member-relative patterns
     * such as `com.example/cache`.
     */
    fun tarScript(parent: String, members: List<String>, excludes: List<String>, selinux: Boolean): String {
        require(members.isNotEmpty()) { "nothing to tar" }
        val args = ArrayList<String>()
        args += listOf("tar", "-c", "-f", "-", "--numeric-owner")
        if (selinux) args += "--selinux"
        excludes.forEach { args += "--exclude=$it" }
        args += listOf("-C", parent)
        args += members
        return PrivilegedStreamScripts.command(args)
    }

    /**
     * `<octal mode> <uid> <gid> <context> <path>` for every entry below [roots], skipping [pruned]
     * absolute dirs (their contents are not archived either).
     */
    fun statListingScript(roots: List<String>, pruned: List<String>): String {
        require(roots.isNotEmpty()) { "no roots" }
        val prune = if (pruned.isEmpty()) "" else "\\( ${pruned.joinToString(" -o ") { "-path ${q(it)}" }} \\) -prune -o "
        return "find ${roots.joinToString(" ") { q(it) }} $prune-exec stat -c '%a %u %g %C %n' {} +"
    }

    /** `stat` of the data dirs themselves for the manifest: `<uid> <gid> <context> <path>`. */
    fun dirOwnerScript(dirs: List<String>): String =
        "for __p in ${dirs.joinToString(" ") { q(it) }}; do if [ -e \"\$__p\" ]; then stat -c '%u %g %C %n' \"\$__p\"; fi; done; true"

    /** `<KiB>\t<path>` per existing path (`du -sk`). */
    fun duScript(paths: List<String>): String =
        "for __p in ${paths.joinToString(" ") { q(it) }}; do if [ -e \"\$__p\" ]; then du -sk \"\$__p\" 2>/dev/null; fi; done; true"

    /** Parse `du -sk` lines (tab or space separated) into absolute path -> bytes. */
    fun parseDu(lines: List<String>): Map<String, Long> {
        val sizes = HashMap<String, Long>()
        for (line in lines) {
            val trimmed = line.trim()
            val split = trimmed.indexOfFirst { it == '\t' || it == ' ' }
            if (split <= 0) continue
            val kib = trimmed.substring(0, split).toLongOrNull() ?: continue
            val path = trimmed.substring(split + 1).trim()
            if (path.startsWith("/")) sizes[path] = kib * 1024
        }
        return sizes
    }

    data class StatLine(val mode: String, val uid: Int, val gid: Int, val context: String?, val path: String)

    /** Parse one `%a %u %g %C %n` line; the path may contain spaces. */
    fun parseStatLine(line: String): StatLine? {
        val parts = line.split(' ', limit = 5)
        if (parts.size < 5) return null
        val mode = parts[0].takeIf { s -> s.isNotEmpty() && s.all { it in '0'..'7' } } ?: return null
        val uid = parts[1].toIntOrNull() ?: return null
        val gid = parts[2].toIntOrNull() ?: return null
        val path = parts[4].takeIf { it.startsWith("/") } ?: return null
        return StatLine(mode, uid, gid, parts[3].takeUnless { it == "?" || it.isEmpty() }, path)
    }

    data class OwnerLine(val uid: Int, val gid: Int, val context: String?, val path: String)

    fun parseOwnerLine(line: String): OwnerLine? {
        val parts = line.split(' ', limit = 4)
        if (parts.size < 4) return null
        val uid = parts[0].toIntOrNull() ?: return null
        val gid = parts[1].toIntOrNull() ?: return null
        val path = parts[3].takeIf { it.startsWith("/") } ?: return null
        return OwnerLine(uid, gid, parts[2].takeUnless { it == "?" || it.isEmpty() }, path)
    }
}
