package ca.devdcs.backup.core.restore.files

import ca.devdcs.backup.core.exec.root.RootScriptBuilder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure shell builders for privileged restore targets. Every path and value goes through the
 * project's audited quoting ([RootScriptBuilder.shellQuote]); kept Android-free so the quoting of
 * root commands is unit-tested. Paths passed here are absolute.
 */
object RestoreScripts {

    /** Ops per root command: long enough to amortize the shell round trip, short for ARG_MAX. */
    const val METADATA_BATCH = 100

    const val FAIL_MARKER = "__DFTB_FAIL__"

    private val TOUCH_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm.ss", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun q(value: String): String = RootScriptBuilder.shellQuote(value)

    /** Prints `L`, `D`, `F <size>`, `O` (other) or `N` (absent) without following a final link. */
    fun lstatScript(absPath: String): String {
        val p = q(absPath)
        return "if [ -L $p ]; then echo L; elif [ -d $p ]; then echo D; " +
            "elif [ -f $p ]; then echo \"F \$(stat -c %s $p 2>/dev/null)\"; elif [ -e $p ]; then echo O; else echo N; fi"
    }

    fun parseLstat(stdout: List<String>): TargetNode? {
        val line = stdout.lastOrNull { it.isNotBlank() }?.trim() ?: return null
        return when {
            line == "L" -> TargetNode(TargetNodeType.SYMLINK)
            line == "D" -> TargetNode(TargetNodeType.DIRECTORY)
            line.startsWith("F") -> TargetNode(TargetNodeType.FILE, line.removePrefix("F").trim().toLongOrNull())
            line == "O" -> TargetNode(TargetNodeType.OTHER)
            else -> null
        }
    }

    fun mkdirScript(absPath: String): String = "mkdir ${q(absPath)}"

    /**
     * Move a temp file into place. With [replace], an existing file or symlink is unlinked first
     * (so `mv` can never move *into* a directory a link points to); a directory is refused.
     */
    fun commitScript(absTemp: String, absFinal: String, replace: Boolean): String {
        val t = q(absTemp)
        val f = q(absFinal)
        val clear = if (replace) "if [ -L $f ] || [ -f $f ]; then rm -f $f || exit 1; fi; " else ""
        return clear + "if [ -e $f ] || [ -L $f ]; then echo 'File exists' >&2; exit 1; fi; mv $t $f"
    }

    fun removeScript(absPath: String): String = "rm -f ${q(absPath)}"

    /** Unlink a non-directory without following it. */
    fun deleteFileScript(absPath: String): String {
        val p = q(absPath)
        return "if [ -d $p ] && [ ! -L $p ]; then echo 'Is a directory' >&2; exit 1; fi; rm -f $p"
    }

    fun symlinkScript(absPath: String, target: String): String = "ln -s -- ${q(target)} ${q(absPath)}"

    fun hardLinkScript(absPath: String, absExisting: String): String {
        val e = q(absExisting)
        return "if [ -L $e ]; then echo 'Hard link source is a symlink' >&2; exit 1; fi; ln -- $e ${q(absPath)}"
    }

    fun dfScript(absPath: String): String = "df -Pk ${q(absPath)}"

    /** `chmod` takes octal; only permission + setuid/setgid/sticky bits. */
    fun octal(mode: Int): String = Integer.toOctalString(mode and 0xFFF)

    fun touchTime(millis: Long): String = TOUCH_TIME.format(Instant.ofEpochMilli(millis))

    /**
     * One batched script applying [ops] (absolute paths). Each failing op prints
     * `FAIL_MARKER <index>` instead of aborting the batch. chmod is never run on a symlink: it is
     * skipped for SYMLINK ops and every other op re-checks `[ ! -L ]` right before it (a path can be
     * swapped for a link after it was restored). chown/chcon/touch use `-h`, so they never follow one.
     */
    fun metadataScript(ops: List<MetadataOp>, ownership: Boolean, mtime: Boolean): String = buildString {
        append("export TZ=UTC; ")
        ops.forEachIndexed { index, op ->
            val p = q(op.path)
            val parts = ArrayList<String>()
            if (ownership) {
                if (op.uid != null && op.gid != null) parts += "chown -h ${op.uid}:${op.gid} $p"
                else if (op.uid != null) parts += "chown -h ${op.uid} $p"
                // Re-checked at apply time: the path may have become a symlink since it was restored.
                if (op.mode != null && op.type != TargetNodeType.SYMLINK) parts += "[ ! -L $p ] && chmod ${octal(op.mode)} $p"
                if (!op.seLinuxContext.isNullOrBlank()) parts += "chcon -h ${q(op.seLinuxContext)} $p"
            }
            if (mtime && op.modifiedAtMillis != null && op.type != TargetNodeType.SYMLINK) {
                parts += "[ ! -L $p ] && touch -h -c -m -t ${touchTime(op.modifiedAtMillis)} $p"
            }
            if (parts.isEmpty()) return@forEachIndexed
            append("{ ").append(parts.joinToString(" && ")).append("; } 2>/dev/null || echo '").append(FAIL_MARKER).append(' ').append(index).append("'; ")
        }
        append("true")
    }

    fun parseFailures(stdout: List<String>): List<Int> =
        stdout.mapNotNull { line -> line.trim().takeIf { it.startsWith(FAIL_MARKER) }?.substringAfter(' ')?.trim()?.toIntOrNull() }

    /** Create/truncate the temp file for the Shizuku chunked writer. */
    fun truncateScript(absPath: String): String = ": > ${q(absPath)}"

    /** Append one base64 chunk from stdin. */
    fun appendBase64Script(absPath: String): String = "base64 -d >> ${q(absPath)}"
}
