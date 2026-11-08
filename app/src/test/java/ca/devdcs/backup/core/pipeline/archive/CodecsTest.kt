package ca.devdcs.backup.core.pipeline.archive

import ca.devdcs.backup.domain.model.CompressionCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class CodecsTest {

    private val payload: ByteArray = ByteArray(200_000).also { arr ->
        val r = Random(7)
        for (i in arr.indices) arr[i] = if (i % 3 == 0) r.nextInt().toByte() else (i % 17).toByte()
    }

    @Test
    fun everyCodecRoundTripsAtEveryLevelIncludingOutOfRange() {
        for (codec in CompressionCodec.entries) {
            for (level in listOf(-5, 0, 1, codec.maxLevel / 2, codec.maxLevel, codec.maxLevel + 10)) {
                val sink = ByteArrayOutputStream()
                Codecs.compress(codec, level, sink).use { it.write(payload) }
                val back = Codecs.decompress(codec, ByteArrayInputStream(sink.toByteArray())).use { it.readBytes() }
                assertArrayEquals("$codec level $level", payload, back)
                if (Codecs.clampLevel(codec, level) > 0) assertTrue("$codec level $level compresses", sink.size() < payload.size)
            }
        }
    }

    @Test
    fun concatenatedMembersAreReadThrough() {
        for (codec in listOf(CompressionCodec.GZIP, CompressionCodec.ZSTD, CompressionCodec.BZIP2, CompressionCodec.XZ)) {
            val sink = ByteArrayOutputStream()
            Codecs.compress(codec, 3, NonClosingOutputStream(sink)).use { it.write("abc".toByteArray()) }
            Codecs.compress(codec, 3, NonClosingOutputStream(sink)).use { it.write("def".toByteArray()) }
            val back = Codecs.decompress(codec, ByteArrayInputStream(sink.toByteArray())).use { it.readBytes() }
            assertEquals(codec.name, "abcdef", String(back))
        }
    }

    @Test
    fun noneIsIdentity() {
        val sink = ByteArrayOutputStream()
        assertSame(sink, Codecs.compress(CompressionCodec.NONE, 5, sink))
        val input = ByteArrayInputStream(ByteArray(1))
        assertSame(input, Codecs.decompress(CompressionCodec.NONE, input))
    }

    @Test
    fun levelsClampToCodecRange() {
        assertEquals(1, Codecs.clampLevel(CompressionCodec.ZSTD, 0))
        assertEquals(19, Codecs.clampLevel(CompressionCodec.ZSTD, 22))
        assertEquals(1, Codecs.clampLevel(CompressionCodec.BZIP2, -1))
        assertEquals(0, Codecs.clampLevel(CompressionCodec.GZIP, -1))
        assertEquals(9, Codecs.clampLevel(CompressionCodec.XZ, 99))
        assertEquals(0, Codecs.clampLevel(CompressionCodec.NONE, 7))
    }

    @Test
    fun xzPresetIsLoweredToFitHeap() {
        val small = 256L * 1024 * 1024
        val options = Codecs.lzma2Options(9, maxHeapBytes = small)
        assertTrue(options.encoderMemoryUsage.toLong() * 1024 <= small / 3)
        assertTrue(options.dictSize < LZMA2Options(9).dictSize)
        assertEquals(LZMA2Options(9).dictSize, Codecs.lzma2Options(9, maxHeapBytes = 8L shl 30).dictSize)
    }

    @Test
    fun zstdTruncationInsideAnyFrameIsDetected() {
        val sink = ByteArrayOutputStream()
        Codecs.compress(CompressionCodec.ZSTD, 3, NonClosingOutputStream(sink)).use { it.write(payload, 0, 50_000) }
        val firstFrameEnd = sink.size()
        Codecs.compress(CompressionCodec.ZSTD, 3, NonClosingOutputStream(sink)).use { it.write(payload, 50_000, 150_000) }
        val all = sink.toByteArray()

        Codecs.decompress(CompressionCodec.ZSTD, ByteArrayInputStream(all)).use { assertArrayEquals(payload, it.readBytes()) }
        // Single-byte reads exercise the partial-output paths.
        Codecs.decompress(CompressionCodec.ZSTD, ByteArrayInputStream(all)).use { input ->
            val out = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) break
                out.write(c)
            }
            assertArrayEquals(payload, out.toByteArray())
        }
        // Sampled cuts plus every cut around the frame boundary and the end, keeping the test fast.
        val cuts = (1 until all.size step 97) + ((firstFrameEnd - 64)..(firstFrameEnd + 64)) + ((all.size - 64) until all.size)
        for (cut in cuts.filter { it in 1 until all.size }) {
            if (cut == firstFrameEnd) continue // a clean frame boundary is indistinguishable from EOF
            try {
                Codecs.decompress(CompressionCodec.ZSTD, ByteArrayInputStream(all.copyOf(cut))).use { it.readBytes() }
                fail("truncation at $cut of ${all.size} not detected")
            } catch (_: java.io.IOException) {
            }
        }
    }
}
