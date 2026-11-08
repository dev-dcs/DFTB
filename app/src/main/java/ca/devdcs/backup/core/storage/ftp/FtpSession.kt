package ca.devdcs.backup.core.storage.ftp

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.RemoteEntry
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.domain.model.Destination
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * One logged-in FTP(S) control connection.
 *
 * Exists to adapt commons-net's stateful, boolean-returning API to the [StorageSession] contract:
 * every path is resolved to an absolute server path under [root] (so CWD calls never shift
 * meaning), every negative reply becomes a [StorageException], and transfer streams finish their
 * pending command on close so an upload only counts as done once the server confirmed it.
 */
internal class FtpSession(
    override val destination: Destination,
    private val client: FTPClient,
    /** Absolute base directory on the server, without trailing slash ("" = server root). */
    private val root: String,
    private val ftps: Boolean,
    private val hasMlst: Boolean,
    private val io: CoroutineDispatcher,
) : StorageSession {

    @Volatile private var closed = false

    override suspend fun spaceInfo(): SpaceInfo? = ftp("AVBL") {
        // AVBL is a draft extension; many servers lack it, which simply means "unknown".
        val code = try {
            client.sendCommand("AVBL", abs(""))
        } catch (e: IOException) {
            if (!client.isConnected) throw e
            return@ftp null
        }
        FtpErrors.parseAvbl(code, client.replyString)?.let { SpaceInfo(freeBytes = it, totalBytes = null) }
    }

    override suspend fun list(path: String): List<RemoteEntry> = ftp("List $path") {
        val rel = NetPaths.normalize(path)
        listInternal(rel) ?: throw StorageException(Reason.NOT_FOUND, "Directory not found: /$rel")
    }

    override suspend fun stat(path: String): RemoteEntry? = ftp("Stat $path") { statInternal(NetPaths.normalize(path)) }

    override suspend fun mkdirs(path: String) = ftp("Create directory $path") { mkdirsInternal(NetPaths.normalize(path)) }

    override suspend fun openWrite(path: String, append: Boolean): OutputStream = ftp("Upload $path") {
        val rel = NetPaths.normalize(path)
        if (rel.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "Cannot write to the base directory itself")
        mkdirsInternal(NetPaths.parent(rel))
        val target = abs(rel)
        val raw: OutputStream = (if (append) client.appendFileStream(target) else client.storeFileStream(target))
            ?: throw FtpErrors.fromReply(if (append) "Append /$rel" else "Upload /$rel", client.replyCode, client.replyString, ftps)
        TransferOutputStream(raw, rel)
    }

    override suspend fun openRead(path: String, offset: Long): InputStream = ftp("Download $path") {
        require(offset >= 0L) { "offset must be >= 0" }
        val rel = NetPaths.normalize(path)
        client.setRestartOffset(offset)
        val raw = client.retrieveFileStream(abs(rel))
        if (raw == null) {
            client.setRestartOffset(0L)
            throw FtpErrors.fromReply("Download /$rel", client.replyCode, client.replyString, ftps)
        }
        TransferInputStream(raw, rel)
    }

    override suspend fun delete(path: String) = ftp("Delete $path") {
        val rel = NetPaths.normalize(path)
        if (rel.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "Refusing to delete the base directory")
        val target = abs(rel)
        if (client.deleteFile(target)) return@ftp
        val fileCode = client.replyCode
        val fileReply = client.replyString
        if (client.removeDirectory(target)) return@ftp
        val entry = statInternal(rel) ?: return@ftp // Already gone: not an error.
        if (entry.isDirectory) {
            throw StorageException(
                FtpErrors.reasonFor(client.replyCode, client.replyString).let { if (it == Reason.NOT_FOUND) Reason.PERMISSION else it },
                "Cannot remove directory /$rel (not empty or not permitted): ${client.replyString?.trim().orEmpty()}",
            )
        }
        throw FtpErrors.fromReply("Delete /$rel", fileCode, fileReply, ftps)
    }

    override suspend fun rename(from: String, to: String) = ftp("Rename $from") {
        val src = NetPaths.normalize(from)
        val dst = NetPaths.normalize(to)
        if (src.isEmpty() || dst.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "Cannot rename the base directory")
        if (src == dst) return@ftp
        if (client.rename(abs(src), abs(dst))) return@ftp
        val firstCode = client.replyCode
        val firstReply = client.replyString
        // Servers such as IIS refuse RNTO onto an existing file; emulate overwrite.
        val existing = statInternal(dst)
        if (existing != null && !existing.isDirectory && statInternal(src) != null) {
            if (!client.deleteFile(abs(dst))) throw FtpErrors.fromReply("Replace /$dst", client.replyCode, client.replyString, ftps)
            if (client.rename(abs(src), abs(dst))) return@ftp
            throw FtpErrors.fromReply("Rename /$src", client.replyCode, client.replyString, ftps)
        }
        if (existing == null && NetPaths.parent(dst).isNotEmpty() && statInternal(NetPaths.parent(dst)) == null) {
            mkdirsInternal(NetPaths.parent(dst))
            if (client.rename(abs(src), abs(dst))) return@ftp
            throw FtpErrors.fromReply("Rename /$src", client.replyCode, client.replyString, ftps)
        }
        throw FtpErrors.fromReply("Rename /$src", firstCode, firstReply, ftps)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (client.isConnected) {
                try {
                    client.logout()
                } catch (_: IOException) {
                    // Server may already have dropped us; disconnect below still frees the socket.
                }
                client.disconnect()
            }
        } catch (e: IOException) {
            DftbLog.w(TAG, "FTP disconnect failed: ${e.message}")
        }
    }

    // --- internals (called on the IO dispatcher) ---

    private fun abs(rel: String): String = absolutePath(root, rel)

    /** Children of [rel], or null when it does not exist (or is not a directory). */
    private fun listInternal(rel: String): List<RemoteEntry>? {
        val target = abs(rel)
        var files: List<FTPFile?>? = null
        if (hasMlst) {
            try {
                val result = client.mlistDir(target)
                if (FTPReply.isPositiveCompletion(client.replyCode)) files = result?.toList()
            } catch (e: IOException) {
                if (!client.isConnected) throw e
            }
        }
        if (files == null) {
            // LIST of a non-directory path lists the file itself on some servers: require CWD to succeed.
            if (!client.changeWorkingDirectory(target)) {
                return if (client.replyCode == 550) null
                else throw FtpErrors.fromReply("List /$rel", client.replyCode, client.replyString, ftps)
            }
            val listed = client.listFiles()
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw FtpErrors.fromReply("List /$rel", client.replyCode, client.replyString, ftps)
            }
            files = listed?.toList().orEmpty()
        }
        return files.orEmpty().asSequence()
            .filterNotNull()
            .filter { it.name != null && it.name.isNotEmpty() && it.name != "." && it.name != ".." && '/' !in it.name }
            .map { it.toEntry(NetPaths.child(rel, it.name)) }
            .toList()
    }

    private fun statInternal(rel: String): RemoteEntry? {
        if (rel.isEmpty()) {
            return RemoteEntry(path = "", name = "", isDirectory = true, sizeBytes = null, modifiedAtMillis = null)
        }
        val target = abs(rel)
        if (hasMlst) {
            try {
                val file = client.mlistFile(target)
                if (file != null) return file.toEntry(rel)
                if (client.replyCode == 550 || client.replyCode == 501) return null
            } catch (e: IOException) {
                if (!client.isConnected) throw e
            }
        }
        if (client.changeWorkingDirectory(target)) {
            return RemoteEntry(path = rel, name = NetPaths.name(rel), isDirectory = true, sizeBytes = null, modifiedAtMillis = null)
        }
        val name = NetPaths.name(rel)
        val siblings = listInternal(NetPaths.parent(rel)) ?: return null
        return siblings.firstOrNull { it.name == name }
    }

    private fun mkdirsInternal(rel: String) {
        val segments = NetPaths.segments(rel)
        var current = ""
        for (segment in segments) {
            current = if (current.isEmpty()) segment else "$current/$segment"
            val target = abs(current)
            if (client.changeWorkingDirectory(target)) continue
            if (client.makeDirectory(target)) continue
            val code = client.replyCode
            val reply = client.replyString
            // A concurrent creator or an odd 550 "exists" reply: re-check before failing.
            if (client.changeWorkingDirectory(target)) continue
            throw FtpErrors.fromReply("Create directory /$current", code, reply, ftps)
        }
    }

    private fun FTPFile.toEntry(rel: String): RemoteEntry = RemoteEntry(
        path = rel,
        name = NetPaths.name(rel),
        isDirectory = isDirectory,
        sizeBytes = if (isDirectory) null else size.takeIf { it >= 0L },
        modifiedAtMillis = timestamp?.timeInMillis,
    )

    private suspend fun <T> ftp(operation: String, block: () -> T): T {
        if (closed) throw StorageException(Reason.PROTOCOL, "$operation: session is closed")
        return withContext(io) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: StorageException) {
                throw e
            } catch (e: IOException) {
                throw FtpErrors.fromIo(operation, e)
            } catch (e: IllegalArgumentException) {
                throw StorageException(Reason.MISCONFIGURED, "$operation: ${e.message}", e)
            } catch (e: RuntimeException) {
                throw StorageException(Reason.PROTOCOL, "$operation: ${e.message}", e)
            }
        }
    }

    /** Upload stream; close() completes the STOR/APPE and throws unless the server confirmed it. */
    private inner class TransferOutputStream(raw: OutputStream, private val rel: String) : FilterOutputStream(raw) {
        private var done = false

        override fun write(b: ByteArray, off: Int, len: Int) {
            try {
                out.write(b, off, len)
            } catch (e: IOException) {
                throw FtpErrors.fromIo("Upload /$rel", e)
            }
        }

        override fun write(b: Int) {
            try {
                out.write(b)
            } catch (e: IOException) {
                throw FtpErrors.fromIo("Upload /$rel", e)
            }
        }

        override fun flush() {
            try {
                out.flush()
            } catch (e: IOException) {
                throw FtpErrors.fromIo("Upload /$rel", e)
            }
        }

        override fun close() {
            if (done) return
            done = true
            var failure: StorageException? = null
            try {
                out.close()
            } catch (e: IOException) {
                failure = FtpErrors.fromIo("Upload /$rel", e)
            }
            // Always read the transfer reply, even after a failed close, so the control channel
            // stays in step; if it cannot be read, the connection is unusable and is dropped.
            try {
                if (!client.completePendingCommand() && failure == null) {
                    failure = FtpErrors.fromReply("Upload /$rel", client.replyCode, client.replyString, ftps)
                }
            } catch (e: IOException) {
                if (failure == null) failure = FtpErrors.fromIo("Upload /$rel", e)
                try {
                    client.disconnect()
                } catch (_: IOException) {
                    // Already broken; the recorded failure is what the caller needs.
                }
            }
            failure?.let { throw it }
        }
    }

    /** Download stream; close() drains the RETR reply so the control connection stays usable. */
    private inner class TransferInputStream(raw: InputStream, private val rel: String) : FilterInputStream(raw) {
        private var done = false
        private var reachedEof = false

        override fun read(): Int = guard { `in`.read().also { if (it < 0) reachedEof = true } }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            guard { `in`.read(b, off, len).also { if (it < 0) reachedEof = true } }

        override fun skip(n: Long): Long = guard { `in`.skip(n) }

        override fun close() {
            if (done) return
            done = true
            try {
                `in`.close()
                val ok = client.completePendingCommand()
                // Closing early makes servers answer 426/451 (transfer aborted): expected, not a failure.
                if (!ok && reachedEof) {
                    throw FtpErrors.fromReply("Download /$rel", client.replyCode, client.replyString, ftps)
                }
            } catch (e: IOException) {
                if (reachedEof) throw FtpErrors.fromIo("Download /$rel", e)
                DftbLog.w(TAG, "Ignoring error while aborting download of /$rel: ${e.message}")
            }
        }

        private inline fun <T> guard(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            throw FtpErrors.fromIo("Download /$rel", e)
        }
    }

    companion object {
        private const val TAG = "FtpSession"

        /**
         * Absolute server directory for the destination base. A blank [basePath] is the login
         * directory [pwd]; a relative one is resolved against it. Returned without trailing slash,
         * "" meaning the server root.
         */
        fun resolveRoot(basePath: String?, pwd: String?): String {
            val home = NetPaths.segments(pwd.orEmpty())
            val base = basePath?.trim().orEmpty()
            val segments = when {
                base.isEmpty() -> home
                base.startsWith("/") -> NetPaths.segments(base)
                else -> home + NetPaths.segments(base)
            }
            return if (segments.isEmpty()) "" else segments.joinToString("/", prefix = "/")
        }

        /** Absolute server path of session-relative [rel] under [root]. */
        fun absolutePath(root: String, rel: String): String {
            val segments = NetPaths.segments(rel)
            if (segments.isEmpty()) return root.ifEmpty { "/" }
            return root + "/" + segments.joinToString("/")
        }
    }
}
