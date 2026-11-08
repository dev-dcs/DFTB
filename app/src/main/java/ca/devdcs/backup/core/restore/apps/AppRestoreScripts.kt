package ca.devdcs.backup.core.restore.apps

import ca.devdcs.backup.core.discovery.apps.AppDataPaths
import ca.devdcs.backup.core.exec.root.RootScriptBuilder

/**
 * Pure builders and parsers for every shell command an app restore runs (`pm`, `am`, `tar`,
 * `chown`, `restorecon`, `chcon`). Android-free so the quoting — security-sensitive on a root
 * shell — and the output parsing are unit-tested. Every path and name is single-quoted; package
 * names are additionally validated by the caller ([AppDataPaths.isValidPackageName]).
 */
object AppRestoreScripts {

    fun q(arg: String): String = RootScriptBuilder.shellQuote(arg)

    private fun line(argv: List<String>): String = argv.joinToString(" ") { q(it) }

    // ---- package manager -----------------------------------------------------------------

    fun forceStop(packageName: String, userId: Int): String = line(listOf("am", "force-stop", "--user", userId.toString(), packageName))

    /** `pm install-create -r --user <u> --pkg <pkg> [-d] [-i <installer>] [-S <total>]`. */
    fun installCreate(packageName: String, userId: Int, totalBytes: Long?, downgrade: Boolean, installer: String?): String {
        require(AppDataPaths.isValidPackageName(packageName)) { "invalid package name" }
        // --pkg makes the session reject an APK set of any other package.
        val argv = arrayListOf("pm", "install-create", "-r", "--user", userId.toString(), "--pkg", packageName)
        if (downgrade) argv += "-d"
        if (installer != null && AppDataPaths.isValidPackageName(installer)) {
            argv += listOf("-i", installer)
        }
        if (totalBytes != null && totalBytes >= 0) argv += listOf("-S", totalBytes.toString())
        return line(argv)
    }

    /** Session id from `Success: created install session [1234]`. */
    fun parseSessionId(output: List<String>): Int? {
        val regex = Regex("\\[(\\d+)]")
        return output.firstNotNullOfOrNull { l -> if (l.contains("Success")) regex.find(l)?.groupValues?.get(1)?.toIntOrNull() else null }
    }

    /**
     * Writes one split from stdin. `cat` sits in front of `pm` so system_server receives an
     * anonymous pipe from the shell rather than the caller's FIFO (whose SELinux label it may not
     * be allowed to read); `pipefail` keeps a short read visible.
     */
    fun installWriteFromStdin(sessionId: Int, sessionName: String, sizeBytes: Long): String =
        "set -o pipefail 2>/dev/null; cat | " + line(listOf("pm", "install-write", "-S", sizeBytes.toString(), sessionId.toString(), sessionName, "-"))

    /** Writes one split from a file the shell can read (Shizuku staging). */
    fun installWriteFromFile(sessionId: Int, sessionName: String, sizeBytes: Long, path: String): String =
        "set -o pipefail 2>/dev/null; cat -- ${q(path)} | " +
            line(listOf("pm", "install-write", "-S", sizeBytes.toString(), sessionId.toString(), sessionName, "-"))

    fun installCommit(sessionId: Int): String = line(listOf("pm", "install-commit", sessionId.toString()))

    fun installAbandon(sessionId: Int): String = line(listOf("pm", "install-abandon", sessionId.toString())) + " 2>/dev/null; true"

    fun installExisting(packageName: String, userId: Int): String =
        line(listOf("pm", "install-existing", "--user", userId.toString(), packageName))

    /** `pm` prints `Success` on success; some releases exit 0 even on `Failure [...]`. */
    fun isPmSuccess(exitCode: Int, output: List<String>): Boolean =
        exitCode == 0 && output.any { it.trim().startsWith("Success") } && output.none { it.trim().startsWith("Failure") || it.trim().startsWith("Error") }

    /** The `Failure [INSTALL_FAILED_...: detail]` / `Error: ...` line, for messages. */
    fun pmFailure(output: List<String>): String? =
        output.map { it.trim() }.firstOrNull { it.startsWith("Failure") || it.startsWith("Error") || it.startsWith("Exception") }

    /** `pm list packages -U --user <u> <pkg>` (the filter is a substring match; [parsePackageUid] picks the exact name). */
    fun listPackageUid(packageName: String, userId: Int): String =
        line(listOf("pm", "list", "packages", "-U", "--user", userId.toString(), packageName))

    /** uid of exactly [packageName] for [userId] from `package:<pkg> uid:<uid>[,<uid>...]` lines. */
    fun parsePackageUid(output: List<String>, packageName: String, userId: Int): Int? {
        for (raw in output) {
            val tokens = raw.trim().split(Regex("\\s+"))
            if (tokens.firstOrNull() != "package:$packageName") continue
            val uids = tokens.firstOrNull { it.startsWith("uid:") }?.removePrefix("uid:")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
            uids.firstOrNull { AppIds.userId(it) == userId }?.let { return it }
            uids.firstOrNull()?.let { appUid -> return AppIds.uid(userId, AppIds.appId(appUid)) }
        }
        return null
    }

    const val LIST_USERS = "pm list users"

    /** User ids from `UserInfo{10:Work profile:1030} running` lines. */
    fun parseUserIds(output: List<String>): Set<Int> {
        val regex = Regex("UserInfo\\{(\\d+):")
        return output.mapNotNull { regex.find(it)?.groupValues?.get(1)?.toIntOrNull() }.toSet()
    }

    /** One `pm grant` per permission; prints `ok <perm>` / `fail <perm> <reason>` lines. */
    fun grantScript(packageName: String, userId: Int, permissions: List<String>): String {
        require(permissions.isNotEmpty()) { "nothing to grant" }
        val head = line(listOf("pm", "grant", "--user", userId.toString(), packageName))
        return "for __p in ${permissions.joinToString(" ") { q(it) }}; do " +
            "__e=\$($head \"\$__p\" 2>&1); if [ \$? -eq 0 ] && ! printf '%s' \"\$__e\" | grep -q -e Exception -e Error; then " +
            "printf 'ok %s\\n' \"\$__p\"; else printf 'fail %s %s\\n' \"\$__p\" \"\$(printf '%s' \"\$__e\" | head -n 1)\"; fi; done; true"
    }

    data class GrantOutcome(val granted: List<String>, val failed: Map<String, String>)

    fun parseGrantOutput(output: List<String>): GrantOutcome {
        val granted = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        for (raw in output) {
            val l = raw.trim()
            when {
                l.startsWith("ok ") -> granted += l.removePrefix("ok ").trim()
                l.startsWith("fail ") -> {
                    val rest = l.removePrefix("fail ")
                    failed[rest.substringBefore(' ')] = rest.substringAfter(' ', "").trim()
                }
            }
        }
        return GrantOutcome(granted, failed)
    }

    // ---- private data --------------------------------------------------------------------

    /** Files below a data dir that are never deleted or restored (installd-owned native-lib link). */
    const val LIB_ENTRY = "lib"

    /**
     * `<kind> <path>` for each of [dirs]: `missing`, `symlink`, `empty` (nothing besides lib /
     * cache / code_cache) or `data`.
     */
    fun dataDirStateScript(dirs: List<String>): String =
        "for __d in ${dirs.joinToString(" ") { q(it) }}; do " +
            "if [ -L \"\$__d\" ]; then printf 'symlink %s\\n' \"\$__d\"; " +
            "elif [ ! -d \"\$__d\" ]; then printf 'missing %s\\n' \"\$__d\"; " +
            "elif [ -n \"\$(find \"\$__d\" -mindepth 1 -maxdepth 1 ! -name lib ! -name cache ! -name code_cache 2>/dev/null | head -n 1)\" ]; then printf 'data %s\\n' \"\$__d\"; " +
            "else printf 'empty %s\\n' \"\$__d\"; fi; done; true"

    enum class DirState { MISSING, SYMLINK, EMPTY, DATA }

    fun parseDirStates(output: List<String>): Map<String, DirState> {
        val states = HashMap<String, DirState>()
        for (raw in output) {
            val l = raw.trimEnd()
            val kind = l.substringBefore(' ')
            val path = l.substringAfter(' ', "")
            if (!path.startsWith("/")) continue
            states[path] = when (kind) {
                "missing" -> DirState.MISSING
                "symlink" -> DirState.SYMLINK
                "empty" -> DirState.EMPTY
                "data" -> DirState.DATA
                else -> continue
            }
        }
        return states
    }

    /** Delete everything inside [dir] except `lib`; refuses a symlinked [dir]. */
    fun wipeDirScript(dir: String): String {
        val d = q(dir)
        return "if [ -L $d ]; then echo 'refusing symlinked data dir' >&2; exit 1; fi; " +
            "if [ -d $d ]; then find $d -mindepth 1 -maxdepth 1 ! -name $LIB_ENTRY -exec rm -rf {} + || exit 1; fi; true"
    }

    /** Extraction from stdin (root FIFO sink). Members are `<pkg>/...` relative to [parent]. */
    fun extractFromStdin(parent: String): String = line(listOf("tar", "-xpf", "-", "-C", parent))

    /** Extraction of a staged tar the shell can read (Shizuku; ownership is managed by FUSE). */
    fun extractFromFile(path: String, parent: String): String = line(listOf("tar", "-xf", path, "-C", parent))

    /**
     * `chown -h` everything in [dir] (and [dir] itself) to [owner], except the `lib` link, then
     * `cache` / `code_cache` trees to [cacheGid] when given.
     */
    fun chownDataDirScript(dir: String, owner: Owner, cacheGid: Int?): String {
        val d = q(dir)
        val main = "${owner.uid}:${owner.gid}"
        val sb = StringBuilder()
        sb.append("chown -h $main $d || exit 1; ")
        sb.append("find $d -mindepth 1 \\( -path ${q("$dir/$LIB_ENTRY")} -prune \\) -o -exec chown -h $main {} + || exit 1; ")
        if (cacheGid != null) {
            for (c in listOf("cache", "code_cache")) {
                val cd = q("$dir/$c")
                sb.append("if [ -d $cd ] && [ ! -L $cd ]; then find $cd -exec chown -h ${owner.uid}:$cacheGid {} + || exit 1; fi; ")
            }
        }
        sb.append("true")
        return sb.toString()
    }

    /** `chown -R -h` of a whole external tree. */
    fun chownTreeScript(dir: String, owner: Owner): String = "if [ -e ${q(dir)} ]; then chown -R -h ${owner.uid}:${owner.gid} ${q(dir)}; fi"

    /**
     * `restorecon` each dir (`-D` so libselinux does not skip /data/data paths; retried without it
     * for builds that lack the flag). Prints `missing` when the tool is absent, else `ok|fail <dir>`.
     */
    fun restoreconScript(dirs: List<String>): String =
        "if ! command -v restorecon >/dev/null 2>&1; then echo missing; exit 0; fi; " +
            "for __d in ${dirs.joinToString(" ") { q(it) }}; do " +
            "if restorecon -R -F -D \"\$__d\" >/dev/null 2>&1 || restorecon -R -F \"\$__d\" >/dev/null 2>&1; then printf 'ok %s\\n' \"\$__d\"; " +
            "else printf 'fail %s\\n' \"\$__d\"; fi; done; true"

    data class RestoreconOutcome(val toolMissing: Boolean, val ok: Set<String>, val failed: Set<String>)

    fun parseRestorecon(output: List<String>): RestoreconOutcome {
        val lines = output.map { it.trim() }
        if (lines.any { it == "missing" }) return RestoreconOutcome(true, emptySet(), emptySet())
        return RestoreconOutcome(
            false,
            lines.filter { it.startsWith("ok ") }.map { it.removePrefix("ok ") }.toSet(),
            lines.filter { it.startsWith("fail ") }.map { it.removePrefix("fail ") }.toSet(),
        )
    }

    /** Fallback labelling with an explicit (recomputed) context. */
    fun chconScript(dir: String, context: String): String = line(listOf("chcon", "-R", "-h", context, dir))

    /** `stat -c '%u %g %C %n'` of each existing path (parse with [parseStatOwner]). */
    fun statOwnerScript(paths: List<String>): String =
        "for __p in ${paths.joinToString(" ") { q(it) }}; do if [ -e \"\$__p\" ]; then stat -c '%u %g %C %n' \"\$__p\"; fi; done; true"

    data class StatOwner(val owner: Owner, val context: String?, val path: String)

    fun parseStatOwner(output: List<String>): Map<String, StatOwner> {
        val result = HashMap<String, StatOwner>()
        for (raw in output) {
            val parts = raw.trim().split(' ', limit = 4)
            if (parts.size < 4) continue
            val uid = parts[0].toIntOrNull() ?: continue
            val gid = parts[1].toIntOrNull() ?: continue
            val path = parts[3].takeIf { it.startsWith("/") } ?: continue
            result[path] = StatOwner(Owner(uid, gid), parts[2].takeUnless { it == "?" || it.isEmpty() }, path)
        }
        return result
    }

    /** Prints the first symlink below [dir] (nothing when there is none or [dir] is absent), `lib` pruned when [keepLib]. */
    fun findSymlinkScript(dir: String, keepLib: Boolean): String {
        val d = q(dir)
        val prune = if (keepLib) "\\( -path ${q("$dir/$LIB_ENTRY")} -prune \\) -o " else ""
        return "if [ -d $d ]; then find $d -mindepth 1 $prune-type l -print | head -n 1; fi; true"
    }

    /** Wipe of an external package dir's contents (kept itself so its owner/quota project survives). */
    fun wipeExternalScript(dir: String): String {
        val d = q(dir)
        return "if [ -L $d ]; then echo 'refusing symlinked dir' >&2; exit 1; fi; " +
            "if [ -d $d ]; then find $d -mindepth 1 -maxdepth 1 -exec rm -rf {} + || exit 1; fi; true"
    }
}
