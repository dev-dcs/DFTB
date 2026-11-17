package ca.devdcs.backup.core.restore.files

import android.content.Context
import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.storage.nfs.RootFifoTransfer
import ca.devdcs.backup.core.storage.ssh.PosixShell
import ca.devdcs.backup.core.storage.ssh.StorageStrings
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Opens a byte sink to a file only a privileged shell can write. */
interface PrivilegedWriter {
    /** [absPath] must lie under [root]; implementations refuse symlinks on the way. */
    suspend fun openWrite(root: String, absPath: String, parentDir: String): OutputStream
}

/**
 * A restore target only reachable through root or Shizuku (other apps' external data, `/data`
 * paths, shared storage without all-files access). Every primitive is one shell command with each
 * path quoted by [RestoreScripts]; content flows through [writer] so the shared root shell is never
 * blocked by a large file. Ownership/mode/SELinux are applied only on the ROOT tier.
 */
class ShellTargetFileSystem(
    private val executor: CommandExecutor,
    /** Absolute target root, no trailing slash. */
    private val root: String,
    private val writer: PrivilegedWriter,
    private val io: CoroutineDispatcher,
    override val label: String = root,
) : TargetFileSystem {

    override val supportsSymlinks: Boolean = true
    override val supportsHardLinks: Boolean = true
    override val canSetOwnership: Boolean = executor.tier == ElevationTier.ROOT
    override val canSetMtime: Boolean = true

    private fun abs(path: String): String = when {
        path.isEmpty() -> root
        root == "/" -> "/$path"
        else -> "$root/$path"
    }

    private suspend fun run(script: String, what: String): CommandResult {
        val result = executor.execute(Command(listOf("sh", "-c", script), timeoutMillis = COMMAND_TIMEOUT_MILLIS))
        if (!result.isSuccess) throw IOException("$what failed: ${result.stderrText.ifBlank { "exit ${result.exitCode}" }}")
        return result
    }

    override suspend fun lstat(path: String): TargetNode? = RestoreScripts.parseLstat(run(RestoreScripts.lstatScript(abs(path)), "stat ${abs(path)}").stdout)

    override suspend fun mkdir(path: String) {
        run(RestoreScripts.mkdirScript(abs(path)), "mkdir ${abs(path)}")
    }

    override suspend fun openTemp(dir: String, finalName: String): TempWrite {
        val tempRel = (if (dir.isEmpty()) "" else "$dir/") + ".dftb-restore-" + UUID.randomUUID() + ".part"
        val out = writer.openWrite(root, abs(tempRel), abs(dir))
        return TempWrite(tempRel, out)
    }

    override suspend fun commit(temp: TempWrite, path: String, replace: Boolean) {
        run(RestoreScripts.commitScript(abs(temp.tempPath), abs(path), replace), "move into ${abs(path)}")
    }

    override suspend fun discard(temp: TempWrite) {
        runCatching { withContext(io) { temp.stream.close() } }
        executor.execute(Command(listOf("sh", "-c", RestoreScripts.removeScript(abs(temp.tempPath))), timeoutMillis = COMMAND_TIMEOUT_MILLIS))
    }

    override suspend fun deleteFile(path: String) {
        run(RestoreScripts.deleteFileScript(abs(path)), "delete ${abs(path)}")
    }

    override suspend fun symlink(path: String, target: String) {
        run(RestoreScripts.symlinkScript(abs(path), target), "symlink ${abs(path)}")
    }

    override suspend fun hardLink(path: String, existing: String) {
        run(RestoreScripts.hardLinkScript(abs(path), abs(existing)), "link ${abs(path)}")
    }

    override suspend fun applyMetadata(ops: List<MetadataOp>, ownership: Boolean): List<String> {
        val failures = ArrayList<String>()
        val absolute = ops.map { it.copy(path = abs(it.path)) }
        for (batch in absolute.chunked(RestoreScripts.METADATA_BATCH)) {
            val script = RestoreScripts.metadataScript(batch, ownership && canSetOwnership, mtime = true)
            val result = executor.execute(Command(listOf("sh", "-c", script), timeoutMillis = COMMAND_TIMEOUT_MILLIS))
            if (!result.isSuccess) {
                failures += batch.map { "${it.path}: ${result.stderrText.ifBlank { "exit ${result.exitCode}" }}" }
                continue
            }
            RestoreScripts.parseFailures(result.stdout).mapNotNullTo(failures) { index -> batch.getOrNull(index)?.path }
        }
        return failures
    }

    override suspend fun freeBytes(): Long? {
        val result = executor.execute(Command(listOf("sh", "-c", RestoreScripts.dfScript(root)), timeoutMillis = COMMAND_TIMEOUT_MILLIS))
        return if (result.isSuccess) PosixShell.parseDf(result.stdout)?.freeBytes else null
    }

    override fun close() = Unit

    companion object {
        const val COMMAND_TIMEOUT_MILLIS = 60_000L
    }
}

/** Root tier: detached FIFO helper (no symlink below [root] on the way to the file). */
internal class RootFifoWriter(
    executor: CommandExecutor,
    cacheDir: java.io.File,
    strings: StorageStrings,
) : PrivilegedWriter {
    private val transfer = RootFifoTransfer(executor, cacheDir, strings)

    override suspend fun openWrite(root: String, absPath: String, parentDir: String): OutputStream =
        transfer.openUpload(root, absPath, parentDir, append = false)
}

/**
 * Shizuku tier: the adb uid cannot open app-private FIFOs, so content travels as base64 chunks on
 * stdin of short `base64 -d >> file` commands. Slower than root, but binary-safe and bounded in
 * memory ([CHUNK_BYTES] per command). The blocking stream API bridges to the suspend executor with
 * runBlocking; callers already write on the IO dispatcher.
 */
internal class ShizukuChunkWriter(private val executor: CommandExecutor) : PrivilegedWriter {

    override suspend fun openWrite(root: String, absPath: String, parentDir: String): OutputStream {
        val guard = PosixShell.symlinkGuard(root, absPath, includeFinal = true)
        val result = executor.execute(Command(listOf("sh", "-c", guard + RestoreScripts.truncateScript(absPath))))
        if (!result.isSuccess) throw IOException("create $absPath failed: ${result.stderrText.ifBlank { "exit ${result.exitCode}" }}")
        return ChunkStream(absPath)
    }

    private inner class ChunkStream(private val absPath: String) : OutputStream() {
        private val buffer = ByteArray(CHUNK_BYTES)
        private var size = 0
        private var closed = false

        override fun write(b: Int) {
            if (size == buffer.size) flushChunk()
            buffer[size++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var remaining = len
            while (remaining > 0) {
                if (size == buffer.size) flushChunk()
                val n = minOf(remaining, buffer.size - size)
                System.arraycopy(b, o, buffer, size, n)
                size += n
                o += n
                remaining -= n
            }
        }

        private fun flushChunk() {
            if (size == 0) return
            val encoded = Base64.getEncoder().encode(buffer.copyOf(size))
            size = 0
            val result = runBlocking {
                executor.execute(Command(listOf("sh", "-c", RestoreScripts.appendBase64Script(absPath)), stdin = encoded, timeoutMillis = ShellTargetFileSystem.COMMAND_TIMEOUT_MILLIS))
            }
            if (!result.isSuccess) throw IOException("write $absPath failed: ${result.stderrText.ifBlank { "exit ${result.exitCode}" }}")
        }

        override fun close() {
            if (closed) return
            closed = true
            flushChunk()
        }
    }

    companion object {
        const val CHUNK_BYTES = 512 * 1024
    }
}

/**
 * Opens [ShellTargetFileSystem]s for the current operating mode. Exists so restore targets get a
 * ready privileged folder without knowing about FIFOs or Shizuku.
 */
@Singleton
class PrivilegedTargets @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: CommandExecutorRouter,
    private val strings: StorageStrings,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** A target rooted at absolute [rootPath], or null when no privileged tier is usable. */
    suspend fun open(rootPath: String): ShellTargetFileSystem? {
        val executor = router.current()
        if (executor.tier == ElevationTier.NONE || !executor.isAvailable()) return null
        val root = rootPath.trimEnd('/').ifEmpty { "/" }
        val writer: PrivilegedWriter = when (executor.tier) {
            ElevationTier.ROOT -> RootFifoWriter(executor, context.cacheDir, strings)
            else -> ShizukuChunkWriter(executor)
        }
        val shell = ShellTargetFileSystem(executor, root, writer, io)
        // Make sure the root exists (privileged mkdir -p; the extractor creates everything below).
        val made = executor.execute(Command(listOf("sh", "-c", "mkdir -p ${RestoreScripts.q(root)}"), timeoutMillis = ShellTargetFileSystem.COMMAND_TIMEOUT_MILLIS))
        return if (made.isSuccess) shell else null
    }
}
