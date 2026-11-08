package ca.devdcs.backup.core.pipeline.archive

import ca.devdcs.backup.domain.model.CompressionCodec
import com.github.luben.zstd.ZstdDecompressCtx
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Codec construction and level policy, kept apart from the archive writers so dd images, raw
 * streams and tar.* all compress identically.
 *
 * ZSTD uses zstd-jni (native, levels 1..19, Android arm64-v8a/armeabi-v7a/x86/x86_64 via the
 * `@aar`, desktop natives via the plain jar in JVM tests). A missing native library surfaces as
 * [ArchiveIoException] with [ArchiveErrorKind.CODEC_UNAVAILABLE], never as a LinkageError.
 */
object Codecs {

    /** Lowest level each codec meaningfully accepts (zstd and bzip2 have no "store" level). */
    fun minLevel(codec: CompressionCodec): Int = when (codec) {
        CompressionCodec.ZSTD, CompressionCodec.BZIP2 -> 1
        CompressionCodec.NONE, CompressionCodec.GZIP, CompressionCodec.XZ -> 0
    }

    /** [level] clamped into the codec's valid range. */
    fun clampLevel(codec: CompressionCodec, level: Int): Int =
        level.coerceIn(minLevel(codec), maxOf(minLevel(codec), codec.maxLevel))

    /**
     * XZ/LZMA2 preset lowered until its encoder fits in a third of the Java heap. Preset 9 needs
     * ~674 MiB, which would OOM a typical 256-512 MiB Android heap; output stays valid xz, only
     * the ratio drops.
     */
    fun lzma2Options(level: Int, maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): LZMA2Options {
        val budgetKiB = maxHeapBytes / 3 / 1024
        var preset = level.coerceIn(0, 9)
        while (preset > 0) {
            val usage = LZMA2Options(preset).encoderMemoryUsage.toLong()
            if (usage <= budgetKiB) break
            preset--
        }
        return LZMA2Options(preset)
    }

    /** Compressor around [out]; closing it finishes the stream and closes [out]. */
    fun compress(codec: CompressionCodec, level: Int, out: OutputStream): OutputStream {
        val l = clampLevel(codec, level)
        return when (codec) {
            CompressionCodec.NONE -> out
            CompressionCodec.GZIP -> GzipCompressorOutputStream(out, GzipParameters().apply { compressionLevel = l })
            CompressionCodec.XZ -> XZOutputStream(out, lzma2Options(l))
            CompressionCodec.BZIP2 -> BZip2CompressorOutputStream(out, l)
            CompressionCodec.ZSTD -> zstd { ZstdOutputStream(out, l) }
        }
    }

    /** Decompressor; concatenated members/frames are read through (pigz/pzstd/pbzip2 output). */
    fun decompress(codec: CompressionCodec, input: InputStream): InputStream = when (codec) {
        CompressionCodec.NONE -> input
        CompressionCodec.GZIP -> GzipCompressorInputStream.builder().setInputStream(input).setDecompressConcatenated(true).get()
        CompressionCodec.XZ -> XZInputStream(input)
        CompressionCodec.BZIP2 -> BZip2CompressorInputStream(input, true)
        CompressionCodec.ZSTD -> zstd { FrameCheckedZstdInputStream(input) }
    }

    private inline fun <T> zstd(block: () -> T): T = try {
        block()
    } catch (e: LinkageError) {
        // UnsatisfiedLinkError / NoClassDefFoundError / ExceptionInInitializerError from Native.load().
        throw ArchiveIoException(ArchiveErrorKind.CODEC_UNAVAILABLE, "zstd native library unavailable: ${e.message}", e)
    }
}

/**
 * Pass-through that ignores close() (flushes instead). Writers stack compressors and archive
 * streams on top of the caller's stream; closing that stack must finish them without closing
 * the caller's [OutputStream], which the caller owns.
 */
internal class NonClosingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
    override fun close() = out.flush()
}

/**
 * zstd decoder that reports truncation anywhere in a multi-frame stream. zstd-jni's
 * ZstdInputStream only notices truncation inside the first frame: once a frame completes it
 * keeps a stale "frame finished" flag while decoding leftover bytes of the next frame, so a cut
 * inside frame 2+ reads as a clean EOF. Here a frame counts as complete only when the
 * decompression context itself reports it fully decoded and flushed.
 */
internal class FrameCheckedZstdInputStream(private val input: InputStream) : InputStream() {
    private val ctx = ZstdDecompressCtx()
    private val srcArray = ByteArray(128 * 1024)
    private val src: ByteBuffer = ByteBuffer.wrap(srcArray).also { it.limit(0) }
    /** True between frames (and before the first): EOF here is a legitimate end. */
    private var frameComplete = true
    private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        while (true) {
            val n = read(one, 0, 1)
            if (n < 0) return -1
            if (n == 1) return one[0].toInt() and 0xFF
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (off < 0 || len < 0 || len > b.size - off) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        while (true) {
            if (src.hasRemaining() || !frameComplete) {
                val dst = ByteBuffer.wrap(b, off, len)
                val before = src.position()
                frameComplete = try {
                    ctx.decompressByteBufferStream(dst, src)
                } catch (e: RuntimeException) {
                    throw IOException("Corrupt zstd stream: ${e.message}", e)
                }
                val produced = dst.position() - off
                if (produced > 0) return produced
                if (src.hasRemaining()) {
                    if (src.position() == before) throw IOException("zstd decoder made no progress")
                    continue
                }
            }
            val n = input.read(srcArray)
            if (n < 0) {
                if (frameComplete) return -1
                throw ArchiveIoException(ArchiveErrorKind.TRUNCATED, "zstd stream ends inside a frame")
            }
            src.position(0)
            src.limit(n)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            input.close()
        } finally {
            ctx.close()
        }
    }
}
