package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.RemoteEntry
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageSession

/**
 * A [StorageSession] whose metadata operations are POSIX shell commands ([PosixShell]). Exists so
 * the SSH exec fallback and the root-mounted NFS session share one tested implementation of list /
 * stat / mkdirs / delete / rename / df; subclasses only supply how a script runs and how bytes are
 * streamed.
 */
internal abstract class PosixShellSession(
    /** Absolute directory on the shell host that contract paths are relative to. */
    protected val basePath: String,
    protected val strings: StorageStrings,
) : StorageSession {

    /** Buffered result of one script. */
    protected data class ShellOutput(val exitCode: Int, val stdout: List<String>, val stderr: String)

    /** Run [script] with a POSIX `sh`, buffering its output. Throws [StorageException] only when it cannot run. */
    protected abstract suspend fun runScript(script: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): ShellOutput

    protected fun abs(relative: String): String = RemotePaths.join(basePath, relative)

    /**
     * Script prefix that refuses to touch [absPath] through symbolic links (see
     * [PosixShell.symlinkGuard]). Empty by default: on an SSH host links are the account owner's
     * business. Root-run sessions on untrusted mounts override it.
     */
    protected open fun confine(absPath: String, includeFinal: Boolean): String = ""

    /** Run [script]; a non-zero exit becomes a classified [StorageException]. */
    protected suspend fun runChecked(operation: String, script: String, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): ShellOutput {
        val out = runScript(script, timeoutMillis)
        if (out.exitCode != 0) {
            val detail = out.stderr.ifBlank { out.stdout.joinToString("\n") }.ifBlank { "exit ${out.exitCode}" }
            throw StorageException(
                PosixShell.classifyError(detail),
                strings.get(R.string.storage_net_op_failed, operation, detail.take(MAX_DETAIL)),
            )
        }
        return out
    }

    override suspend fun spaceInfo(): SpaceInfo? {
        val out = runScript(confine(abs(""), true) + PosixShell.dfScript(abs("")))
        if (out.exitCode != 0) {
            DftbLog.w(TAG, "df failed (exit ${out.exitCode}); capacity unknown")
            return null
        }
        return PosixShell.parseDf(out.stdout)
    }

    override suspend fun list(path: String): List<RemoteEntry> {
        val rel = RemotePaths.normalize(path)
        val out = runChecked("list", confine(abs(rel), true) + PosixShell.listScript(abs(rel)))
        if (out.stdout.firstOrNull()?.trim() == PosixShell.NOT_DIR_MARKER) {
            throw StorageException(StorageException.Reason.NOT_FOUND, strings.get(R.string.storage_net_not_a_directory, rel.ifEmpty { "/" }))
        }
        return out.stdout.mapNotNull { PosixShell.parseStatLine(it) }.map { line ->
            RemoteEntry(
                path = RemotePaths.child(rel, line.name),
                name = line.name,
                isDirectory = line.isDirectory,
                sizeBytes = if (line.isDirectory) null else line.sizeBytes,
                modifiedAtMillis = line.modifiedAtMillis,
            )
        }
    }

    override suspend fun stat(path: String): RemoteEntry? {
        val rel = RemotePaths.normalize(path)
        val out = runChecked("stat", confine(abs(rel), false) + PosixShell.statScript(abs(rel)))
        val first = out.stdout.firstOrNull { it.isNotBlank() } ?: return null
        if (first.trim() == PosixShell.ABSENT_MARKER) return null
        val line = PosixShell.parseStatLine(first) ?: return null
        val name = RemotePaths.name(rel)
        return RemoteEntry(
            path = rel,
            name = name,
            isDirectory = line.isDirectory,
            sizeBytes = if (line.isDirectory) null else line.sizeBytes,
            modifiedAtMillis = line.modifiedAtMillis,
        )
    }

    override suspend fun mkdirs(path: String) {
        runChecked("mkdirs", confine(abs(path), true) + PosixShell.mkdirsScript(abs(path)))
    }

    override suspend fun delete(path: String) {
        val rel = RemotePaths.normalize(path)
        if (rel.isEmpty()) return // never remove the base itself
        runChecked("delete", confine(abs(rel), false) + PosixShell.deleteScript(abs(rel)))
    }

    override suspend fun rename(from: String, to: String) {
        val toRel = RemotePaths.normalize(to)
        // The target is checked including itself: `mv` onto a linked directory would move *into* it.
        val guard = confine(abs(from), false) + confine(abs(toRel), true)
        runChecked("rename", guard + PosixShell.renameScript(abs(from), abs(toRel), abs(RemotePaths.parent(toRel))))
    }

    protected companion object {
        const val TAG = "PosixShellSession"
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        const val MAX_DETAIL = 500
    }
}
