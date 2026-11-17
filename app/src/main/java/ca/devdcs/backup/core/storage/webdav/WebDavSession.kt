package ca.devdcs.backup.core.storage.webdav

import ca.devdcs.backup.core.storage.RemoteEntry
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.core.storage.ftp.NetPaths
import ca.devdcs.backup.domain.model.Destination
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.Pipe
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resumeWithException

/**
 * One WebDAV "connection": a configured [OkHttpClient] plus the destination's base collection URL.
 *
 * Exists to express the [StorageSession] contract in RFC 4918 verbs: PROPFIND (stat/list/quota),
 * MKCOL, streamed PUT, ranged GET, DELETE and MOVE. Uploads are streamed through an okio [Pipe] so
 * archives never need to be staged or sized in advance (chunked transfer encoding).
 */
internal class WebDavSession(
    override val destination: Destination,
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val io: CoroutineDispatcher,
) : StorageSession {

    @Volatile private var closed = false
    private val inFlight: MutableSet<Call> = Collections.synchronizedSet(HashSet())

    /** Confirms the base collection exists and the credential is accepted. */
    suspend fun verifyBase() {
        val response = propfind(baseUrl, depth = 0, body = PROPFIND_ENTRY, operation = "Connect")
        response.use {
            if (it.code == 404) throw StorageException(Reason.NOT_FOUND, "Base folder does not exist on the server: ${baseUrl.encodedPath}")
            if (it.code != 207) throw WebDavErrors.fromResponse("Connect", it)
            val self = parseBody(it, "Connect").firstOrNull { r -> WebDavUrls.relativePath(baseUrl, r.href) == "" }
            if (self != null && !self.isCollection) {
                throw StorageException(Reason.MISCONFIGURED, "Base path points to a file, not a folder")
            }
        }
    }

    override suspend fun spaceInfo(): SpaceInfo? {
        val response = propfind(baseUrl, depth = 0, body = PROPFIND_QUOTA, operation = "Quota")
        return response.use {
            if (it.code == 401) throw WebDavErrors.fromResponse("Quota", it)
            if (it.code != 207) return null // Quota is optional (RFC 4331); unknown is not an error.
            val resources = try {
                parseBody(it, "Quota")
            } catch (e: StorageException) {
                if (e.reason == Reason.PROTOCOL) return null
                throw e
            }
            val self = resources.firstOrNull { r -> WebDavUrls.relativePath(baseUrl, r.href) == "" } ?: resources.firstOrNull()
            val free = self?.quotaAvailableBytes
            val used = self?.quotaUsedBytes
            if (free == null && used == null) return null
            SpaceInfo(freeBytes = free, totalBytes = if (free != null && used != null) free + used else null)
        }
    }

    override suspend fun list(path: String): List<RemoteEntry> {
        val rel = NetPaths.normalize(path)
        val url = WebDavUrls.resolve(baseUrl, rel, collection = true)
        val response = propfind(url, depth = 1, body = PROPFIND_ENTRY, operation = "List /$rel")
        val resources = response.use {
            if (it.code != 207) throw WebDavErrors.fromResponse("List /$rel", it)
            parseBody(it, "List /$rel")
        }
        val entries = ArrayList<RemoteEntry>()
        for (resource in resources) {
            val resourceRel = WebDavUrls.relativePath(baseUrl, resource.href) ?: continue
            if (resourceRel == rel) {
                if (!resource.isCollection) throw StorageException(Reason.NOT_FOUND, "Not a directory: /$rel")
                continue
            }
            // Only direct children; a few servers ignore Depth and return deeper members.
            if (NetPaths.parent(resourceRel) != rel) continue
            entries += resource.toEntry(resourceRel)
        }
        return entries
    }

    override suspend fun stat(path: String): RemoteEntry? = statInternal(NetPaths.normalize(path))

    override suspend fun mkdirs(path: String) {
        val rel = NetPaths.normalize(path)
        if (rel.isEmpty()) return
        val existing = statInternal(rel)
        if (existing != null) {
            if (existing.isDirectory) return
            throw StorageException(Reason.MISCONFIGURED, "A file already exists where a folder is needed: /$rel")
        }
        var current = ""
        for (segment in NetPaths.segments(rel)) {
            current = NetPaths.child(current, segment)
            val url = WebDavUrls.resolve(baseUrl, current, collection = true)
            val operation = "Create folder /$current"
            execute(Request.Builder().url(url).method("MKCOL", null).build(), operation).use {
                // 201 Created; 405 Method Not Allowed = already exists.
                if (!it.isSuccessful && it.code != 405) throw WebDavErrors.fromResponse(operation, it)
            }
        }
    }

    override suspend fun openWrite(path: String, append: Boolean): OutputStream {
        val rel = NetPaths.normalize(path)
        if (append) throw StorageException(Reason.UNSUPPORTED, "WebDAV cannot append to an existing file")
        if (rel.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "Cannot write to the base folder itself")
        ensureOpen("Upload /$rel")
        mkdirs(NetPaths.parent(rel))
        return withContext(io) { PutStream(WebDavUrls.resolve(baseUrl, rel, collection = false), rel) }
    }

    override suspend fun openRead(path: String, offset: Long): InputStream {
        require(offset >= 0L) { "offset must be >= 0" }
        val rel = NetPaths.normalize(path)
        val operation = "Download /$rel"
        val request = Request.Builder().url(WebDavUrls.resolve(baseUrl, rel, collection = false)).get()
        if (offset > 0L) request.header("Range", "bytes=$offset-")
        val call = client.newCall(request.build())
        val response = executeCall(call, operation, keepInFlight = true)
        try {
            when {
                response.code == 416 && offset > 0L -> {
                    response.close()
                    inFlight -= call
                    return ByteArrayInputStream(ByteArray(0)) // Offset at or past EOF: nothing left.
                }
                response.code == 206 -> Unit
                response.code == 200 -> if (offset > 0L) withContext(io) { skipFully(response.body.byteStream(), offset, operation) }
                else -> throw WebDavErrors.fromResponse(operation, response)
            }
            return ResponseInputStream(response, operation) { inFlight -= call }
        } catch (e: Throwable) {
            response.close()
            inFlight -= call
            throw e
        }
    }

    override suspend fun delete(path: String) {
        val rel = NetPaths.normalize(path)
        if (rel.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "Refusing to delete the base folder")
        val entry = statInternal(rel) ?: return
        // WebDAV DELETE on a collection is recursive; the contract only allows empty directories.
        if (entry.isDirectory && list(rel).isNotEmpty()) {
            throw StorageException(Reason.PERMISSION, "Folder is not empty: /$rel")
        }
        val operation = "Delete /$rel"
        val url = WebDavUrls.resolve(baseUrl, rel, collection = entry.isDirectory)
        execute(Request.Builder().url(url).delete().build(), operation).use {
            if (!it.isSuccessful && it.code != 404) throw WebDavErrors.fromResponse(operation, it)
        }
    }

    override suspend fun rename(from: String, to: String) {
        val src = NetPaths.normalize(from)
        val dst = NetPaths.normalize(to)
        if (src.isEmpty() || dst.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "Cannot rename the base folder")
        if (src == dst) return
        val entry = statInternal(src) ?: throw StorageException(Reason.NOT_FOUND, "Not found: /$src")
        val operation = "Rename /$src"
        val request = Request.Builder()
            .url(WebDavUrls.resolve(baseUrl, src, collection = entry.isDirectory))
            .method("MOVE", null)
            .header("Destination", WebDavUrls.resolve(baseUrl, dst, collection = entry.isDirectory).toString())
            .header("Overwrite", "T")
            .build()
        val firstCode = execute(request, operation).use { it.code }
        if (firstCode in 200..299) return
        if (firstCode == 409) {
            // Destination parent missing: create it and retry once.
            mkdirs(NetPaths.parent(dst))
            execute(request, operation).use {
                if (!it.isSuccessful) throw WebDavErrors.fromResponse(operation, it)
            }
            return
        }
        throw WebDavErrors.fromStatus(operation, firstCode, null)
    }

    override fun close() {
        if (closed) return
        closed = true
        val calls = synchronized(inFlight) { inFlight.toList().also { inFlight.clear() } }
        calls.forEach { it.cancel() }
    }

    // --- internals ---

    private suspend fun statInternal(rel: String): RemoteEntry? {
        val operation = "Stat /$rel"
        // Without a trailing slash: works for files, and servers answer collections too.
        val url = WebDavUrls.resolve(baseUrl, rel, collection = rel.isEmpty())
        var response = propfind(url, depth = 0, body = PROPFIND_ENTRY, operation = operation)
        if (response.code in 300..399 && rel.isNotEmpty()) {
            // Redirects are not followed; servers redirect "/dir" to "/dir/", so ask for the collection form.
            response.close()
            response = propfind(WebDavUrls.resolve(baseUrl, rel, collection = true), depth = 0, body = PROPFIND_ENTRY, operation = operation)
        }
        val resources = response.use {
            if (it.code == 404 || it.code == 410) return null
            if (it.code != 207) throw WebDavErrors.fromResponse(operation, it)
            parseBody(it, operation)
        }
        val resource = resources.firstOrNull { WebDavUrls.relativePath(baseUrl, it.href) == rel }
            ?: resources.firstOrNull()
            ?: return null
        return resource.toEntry(rel)
    }

    private suspend fun propfind(url: HttpUrl, depth: Int, body: String, operation: String): Response {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body.toRequestBody(XML))
            .header("Depth", depth.toString())
            .build()
        return execute(request, operation)
    }

    private suspend fun parseBody(response: Response, operation: String): List<DavResource> = withContext(io) {
        try {
            response.body.byteStream().use { PropfindParser.parse(it) }
        } catch (e: IOException) {
            throw WebDavErrors.fromIo(operation, e)
        }
    }

    /** Runs [request] asynchronously; coroutine cancellation cancels the HTTP call. */
    private suspend fun execute(request: Request, operation: String): Response =
        executeCall(client.newCall(request), operation, keepInFlight = false)

    /**
     * Runs [call]; with [keepInFlight] a successful call stays registered (so close() can abort a
     * streaming body) and the caller must remove it once the body is released.
     */
    private suspend fun executeCall(call: Call, operation: String, keepInFlight: Boolean): Response {
        ensureOpen(operation)
        inFlight += call
        var succeeded = false
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(WebDavErrors.fromIo(operation, e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }.also { succeeded = true }
        } finally {
            if (!keepInFlight || !succeeded) inFlight -= call
        }
    }

    private fun ensureOpen(operation: String) {
        if (closed) throw StorageException(Reason.PROTOCOL, "$operation: session is closed")
    }

    private fun skipFully(stream: InputStream, count: Long, operation: String) {
        var remaining = count
        val scratch = ByteArray(SKIP_BUFFER)
        try {
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                val read = stream.read(scratch, 0, minOf(remaining, scratch.size.toLong()).toInt())
                if (read < 0) return // Offset beyond EOF: the stream is simply exhausted.
                remaining -= read
            }
        } catch (e: IOException) {
            throw WebDavErrors.fromIo(operation, e)
        }
    }

    private fun DavResource.toEntry(rel: String) = RemoteEntry(
        path = rel,
        name = NetPaths.name(rel),
        isDirectory = isCollection,
        sizeBytes = contentLength,
        modifiedAtMillis = lastModifiedMillis,
    )

    /** GET body; maps read errors to [StorageException] and releases the connection on close. */
    private class ResponseInputStream(
        private val response: Response,
        private val operation: String,
        private val onClose: () -> Unit,
    ) : FilterInputStream(response.body.byteStream()) {
        override fun read(): Int = guard { `in`.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = guard { `in`.read(b, off, len) }
        override fun skip(n: Long): Long = guard { `in`.skip(n) }
        override fun close() {
            try {
                response.close()
            } finally {
                onClose()
            }
        }

        private inline fun <T> guard(block: () -> T): T = try {
            block()
        } catch (e: IOException) {
            throw WebDavErrors.fromIo(operation, e)
        }
    }

    /**
     * Streaming PUT. Bytes written here flow through an okio [Pipe] into a one-shot request body
     * that OkHttp consumes on its dispatcher thread. close() ends the body and blocks until the
     * server answers; anything but 2xx throws, so a returned close() means the file is stored.
     */
    private inner class PutStream(url: HttpUrl, private val rel: String) : OutputStream() {
        private val operation = "Upload /$rel"
        private val pipe = Pipe(PIPE_BUFFER_BYTES)
        private val sink = pipe.sink.buffer()
        private val result = CompletableFuture<Response>()
        private val call: Call
        private var done = false

        init {
            val body = object : RequestBody() {
                override fun contentType(): MediaType = OCTET_STREAM
                override fun contentLength(): Long = -1L
                override fun isOneShot(): Boolean = true
                override fun writeTo(sink: BufferedSink) {
                    pipe.source.use { source -> sink.writeAll(source) }
                }
            }
            call = client.newCall(Request.Builder().url(url).put(body).build())
            inFlight += call
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    inFlight -= call
                    result.completeExceptionally(e) // Before cancel: the writer must see the cause.
                    pipe.cancel() // Unblock a writer waiting on a full pipe.
                }

                override fun onResponse(call: Call, response: Response) {
                    inFlight -= call
                    // Publish first so a writer failing on the cancelled pipe reports this status.
                    if (!result.complete(response)) response.close()
                    pipe.cancel() // The server answered (possibly early, e.g. 401/507): stop accepting bytes.
                }
            })
        }

        override fun write(b: Int) {
            guard { sink.writeByte(b) }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            guard { sink.write(b, off, len) }
        }

        override fun flush() {
            guard { sink.flush() }
        }

        override fun close() {
            if (done) return
            done = true
            try {
                sink.close()
            } catch (e: IOException) {
                // Pipe cancelled because the call already finished; the response explains why.
                if (!settles()) {
                    abandon()
                    throw WebDavErrors.fromIo(operation, e)
                }
            }
            val response = awaitResult()
            response.use {
                if (!it.isSuccessful) throw WebDavErrors.fromResponse(operation, it)
            }
        }

        private fun awaitResult(): Response = try {
            result.get()
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is IOException) throw WebDavErrors.fromIo(operation, cause)
            throw StorageException(Reason.PROTOCOL, "$operation failed: ${cause?.message}", cause)
        } catch (e: InterruptedException) {
            call.cancel()
            Thread.currentThread().interrupt()
            throw StorageException(Reason.NETWORK, "$operation interrupted", e)
        }

        private inline fun guard(block: () -> Unit) {
            if (done) throw StorageException(Reason.PROTOCOL, "$operation: stream is closed")
            try {
                block()
            } catch (e: IOException) {
                done = true
                if (settles()) {
                    // Server ended the request early: report its status rather than "pipe canceled".
                    awaitResult().use {
                        if (it.isSuccessful) throw StorageException(Reason.PROTOCOL, "$operation: server ended the upload early")
                        throw WebDavErrors.fromResponse(operation, it)
                    }
                }
                abandon()
                throw WebDavErrors.fromIo(operation, e)
            }
        }

        /** True once the call outcome is known, waiting briefly for callbacks racing the pipe cancel. */
        private fun settles(): Boolean {
            if (result.isDone) return true
            return try {
                result.get(SETTLE_GRACE_MS, TimeUnit.MILLISECONDS)
                true
            } catch (_: TimeoutException) {
                false
            } catch (_: ExecutionException) {
                true
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        /** Gives up on the call; a response arriving later is closed so its connection is freed. */
        private fun abandon() {
            call.cancel()
            result.thenAccept { it.close() }
        }
    }

    private companion object {
        val XML: MediaType = "application/xml; charset=utf-8".toMediaType()
        val OCTET_STREAM: MediaType = "application/octet-stream".toMediaType()
        const val PIPE_BUFFER_BYTES = 256L * 1024L
        const val SKIP_BUFFER = 64 * 1024
        const val SETTLE_GRACE_MS = 5_000L

        const val PROPFIND_ENTRY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
        const val PROPFIND_QUOTA = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:quota-available-bytes/><d:quota-used-bytes/></d:prop></d:propfind>"""
    }
}
