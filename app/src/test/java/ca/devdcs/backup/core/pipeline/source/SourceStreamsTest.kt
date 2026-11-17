package ca.devdcs.backup.core.pipeline.source

import ca.devdcs.backup.core.exec.CommandEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Base64

class SourceStreamsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `exact length passes when sizes match`() {
        val data = ByteArray(10_000) { it.toByte() }
        val read = ExactLengthInputStream(ByteArrayInputStream(data), data.size.toLong(), "f").readBytes()
        assertArrayEquals(data, read)
    }

    @Test
    fun `exact length fails on a short file`() {
        try {
            ExactLengthInputStream(ByteArrayInputStream(ByteArray(5)), 6, "f").readBytes()
            fail("expected failure")
        } catch (e: SourceReadException) {
            assertTrue(e.message!!.contains("expected 6 bytes, got 5"))
        }
    }

    @Test(expected = SourceReadException::class)
    fun `exact length fails on a grown file`() {
        ExactLengthInputStream(ByteArrayInputStream(ByteArray(7)), 6, "f").readBytes()
    }

    @Test
    fun `sha256 recorded only at eof`() {
        val data = "hello twrp".toByteArray()
        val book = DigestBook()
        val stream = Sha256RecordingInputStream(ByteArrayInputStream(data), book, "boot.emmc.win")
        stream.read(ByteArray(3))
        assertNull(book.get("boot.emmc.win"))
        stream.readBytes()
        val expected = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        assertEquals(expected, book.get("boot.emmc.win"))
        val partial = DigestBook()
        Sha256RecordingInputStream(ByteArrayInputStream(data), partial, "x").use { it.read() }
        assertNull(partial.get("x"))
    }

    @Test
    fun `stat listing becomes valid json`() {
        val listing = "771 10100 10100 u:object_r:app_data_file:s0:c100,c256 /data/data/a\n" +
            "660 10100 10100 ? /data/data/a/f \"q\".db\n" +
            "broken line\n"
        val text = StatListingJsonInputStream(ByteArrayInputStream(listing.toByteArray())).readBytes().toString(Charsets.UTF_8)
        val root = Json.parseToJsonElement(text).jsonObject
        val entries = root["entries"]!!.jsonArray
        assertEquals(2, entries.size)
        assertEquals("/data/data/a/f \"q\".db", entries[1].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("660", entries[1].jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("null", entries[1].jsonObject["context"].toString())
        assertEquals(1, root["unparsed"]!!.jsonPrimitive.int)
        val empty = Json.parseToJsonElement(StatListingJsonInputStream(ByteArrayInputStream(ByteArray(0))).readBytes().toString(Charsets.UTF_8)).jsonObject
        assertEquals(0, empty["entries"]!!.jsonArray.size)
    }

    private fun lines(data: ByteArray, width: Int): List<String> =
        Base64.getEncoder().encodeToString(data).chunked(width)

    @Test
    fun `base64 lines decode to the original bytes`() {
        val data = ByteArray(100_000) { (it * 31).toByte() }
        val events = flow {
            lines(data, 4 * 1000).forEach { emit(CommandEvent.Stdout(it)) }
            emit(CommandEvent.Exit(0, 1))
        }
        val read = Base64LineInputStream(events, scope, Dispatchers.IO, "t").use { it.readBytes() }
        assertArrayEquals(data, read)
    }

    @Test
    fun `non-zero exit surfaces at eof with stderr`() {
        val events = flowOf(
            CommandEvent.Stdout(Base64.getEncoder().encodeToString("partial".toByteArray())),
            CommandEvent.Stderr("tar: short read"),
            CommandEvent.Exit(1, 1),
        )
        val stream = Base64LineInputStream(events, scope, Dispatchers.IO, "data.tar")
        val buffer = ByteArray(7)
        assertEquals(7, stream.read(buffer))
        try {
            stream.read()
            fail("expected failure")
        } catch (e: SourceReadException) {
            assertTrue(e.message!!.contains("exit 1"))
            assertTrue(e.message!!.contains("tar: short read"))
        }
    }

    @Test(expected = SourceReadException::class)
    fun `stream without exit event is a failure`() {
        Base64LineInputStream(flowOf(CommandEvent.Stdout("aGk=")), scope, Dispatchers.IO, "t").readBytes()
    }

    @Test(expected = SourceReadException::class)
    fun `corrupt base64 is a failure`() {
        Base64LineInputStream(flowOf(CommandEvent.Stdout("!!!"), CommandEvent.Exit(0, 1)), scope, Dispatchers.IO, "t").readBytes()
    }
}
