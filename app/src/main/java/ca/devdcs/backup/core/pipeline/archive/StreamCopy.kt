package ca.devdcs.backup.core.pipeline.archive

import ca.devdcs.backup.core.pipeline.SourceItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Cancellable byte pumping shared by all writers, plus the temp-file spool used when a format
 * needs an entry size the source cannot promise. Must run on the IO dispatcher (callers wrap).
 */
internal object StreamCopy {

    const val BUFFER_SIZE = 64 * 1024

    /** Copies [input] to EOF into [sink]; checks for cancellation every buffer. Returns bytes copied. */
    suspend fun copyAll(input: InputStream, sink: (ByteArray, Int, Int) -> Unit): Long {
        val buf = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf)
            if (n < 0) return total
            if (n > 0) {
                sink(buf, 0, n)
                total += n
            }
        }
    }

    /**
     * Copies exactly [size] bytes and verifies the source then hits EOF, so a file that grew or
     * shrank mid-backup fails loudly instead of corrupting the container.
     */
    suspend fun copyExactly(input: InputStream, size: Long, path: String, sink: (ByteArray, Int, Int) -> Unit) {
        val buf = ByteArray(BUFFER_SIZE)
        var remaining = size
        while (remaining > 0) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) {
                throw ArchiveIoException(
                    ArchiveErrorKind.SIZE_MISMATCH,
                    "Source for '$path' ended after ${size - remaining} of $size declared bytes",
                )
            }
            sink(buf, 0, n)
            remaining -= n
        }
        if (input.read() >= 0) {
            throw ArchiveIoException(ArchiveErrorKind.SIZE_MISMATCH, "Source for '$path' produced more than the declared $size bytes")
        }
    }

    /**
     * A File or Stream item resolved to a known size. [spool] is the temp file backing it (when
     * the source size was not exact); [release] deletes it.
     */
    class Sized(val size: Long, val open: suspend () -> InputStream, private val spool: File?) {
        fun release() {
            spool?.delete()
        }
    }

    /**
     * Resolves an item's exact size. Files and exact-size streams pass straight through; other
     * streams are spooled to a temp file in [stagingDir] first, which needs free space equal to
     * the stream's full size.
     */
    suspend fun sized(item: SourceItem, stagingDir: File): Sized = when (item) {
        is SourceItem.File -> Sized(item.sizeBytes, item.open, null)
        is SourceItem.Stream -> {
            val exact = item.expectedSizeBytes
            if (item.sizeIsExact && exact != null && exact >= 0) {
                Sized(exact, item.open, null)
            } else {
                spool(item, stagingDir)
            }
        }
        else -> throw IllegalArgumentException("Not a byte item: ${item::class.simpleName}")
    }

    private suspend fun spool(item: SourceItem.Stream, stagingDir: File): Sized {
        if (!stagingDir.isDirectory && !stagingDir.mkdirs() && !stagingDir.isDirectory) {
            throw IOException("Cannot create staging directory ${stagingDir.absolutePath}")
        }
        val tmp = File.createTempFile("dftb-spool-", ".part", stagingDir)
        try {
            val size = item.open().use { input ->
                FileOutputStream(tmp).use { fos -> copyAll(input) { b, o, l -> fos.write(b, o, l) } }
            }
            return Sized(size, { FileInputStream(tmp) }, tmp)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }
}
