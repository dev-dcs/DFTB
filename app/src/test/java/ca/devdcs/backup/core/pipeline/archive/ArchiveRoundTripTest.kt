package ca.devdcs.backup.core.pipeline.archive

import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.SourceItem
import ca.devdcs.backup.domain.model.ArchiveFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class ArchiveRoundTripTest {

    private lateinit var root: File
    private lateinit var staging: File
    private lateinit var factory: DefaultArchiveFactory

    @Before
    fun setUp() {
        root = Files.createTempDirectory("archive-test").toFile()
        staging = File(root, "staging")
        factory = DefaultArchiveFactory({ staging }, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** Tracks close() so we can prove writers never close the caller's stream. */
    private class TrackingSink : ByteArrayOutputStream() {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    private val mtime = 1_700_000_000_000L
    private val selinux = "u:object_r:app_data_file:s0:c512,c768"
    private val longName = "data/" + "very_long_directory_name_".repeat(8) + "/file.bin"
    private val fileBytes = ByteArray(70_000) { (it * 31 % 251).toByte() }
    private val streamBytes = "inexact stream payload".repeat(500).toByteArray()

    private fun md(mode: Int) = EntryMetadata(modifiedAtMillis = mtime, mode = mode, uid = 10123, gid = 20123, seLinuxContext = selinux)

    private fun items(): List<SourceItem> = listOf(
        SourceItem.Directory("data/", md(0x1F9)), // 0771
        SourceItem.File("data/blob.bin", fileBytes.size.toLong(), md(0x1B0)) { ByteArrayInputStream(fileBytes) }, // 0660
        SourceItem.File("data/empty", 0, md(0x180)) { ByteArrayInputStream(ByteArray(0)) }, // 0600
        SourceItem.Symlink("data/link", "../lib/arm64", md(0x1FF)),
        SourceItem.Stream("data/exact.raw", fileBytes.size.toLong(), sizeIsExact = true, metadata = md(0x1A4)) { ByteArrayInputStream(fileBytes) },
        SourceItem.Stream("data/inexact.tar", null, sizeIsExact = false, metadata = md(0x1A4)) { ByteArrayInputStream(streamBytes) },
        SourceItem.File(longName, 3, md(0x1A4)) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
    )

    private fun write(format: ArchiveFormat, level: Int, items: List<SourceItem> = items()): ByteArray = runBlocking {
        val sink = TrackingSink()
        factory.writer(format, sink, level).use { w ->
            items.forEach { w.write(it) }
            w.finish()
        }
        assertFalse("$format closed caller stream", sink.closed)
        sink.toByteArray()
    }

    private data class Read(val info: ArchiveEntryInfo, val bytes: ByteArray)

    private fun read(format: ArchiveFormat, bytes: ByteArray, viaFile: Boolean): Map<String, Read> = runBlocking {
        val source = if (viaFile) {
            ArchiveReadSource.LocalFile(File(root, "in.${format.storageKey}").also { it.writeBytes(bytes) })
        } else {
            ArchiveReadSource.Streamed { ByteArrayInputStream(bytes) }
        }
        factory.reader(format, source).use { r ->
            r.entries().associate { e -> e.info.path to Read(e.info, e.stream.readBytes()) }
        }
    }

    private val archiveFormats = listOf(
        ArchiveFormat.TAR, ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST, ArchiveFormat.TAR_BZ2,
        ArchiveFormat.ZIP, ArchiveFormat.SEVEN_ZIP,
    )

    @Test
    fun everyArchiveFormatRoundTripsContentAndMetadata() {
        for (format in archiveFormats) {
            for (level in setOf(0, 1, format.maxCompressionLevel, 99)) {
                val bytes = write(format, level)
                for (viaFile in listOf(true, false)) {
                    val entries = read(format, bytes, viaFile)
                    val tag = "$format/L$level/file=$viaFile"
                    assertEquals(tag, setOf("data", "data/blob.bin", "data/empty", "data/link", "data/exact.raw", "data/inexact.tar", longName), entries.keys)

                    assertEquals(tag, ArchiveEntryType.DIRECTORY, entries.getValue("data").info.type)
                    assertArrayEquals(tag, fileBytes, entries.getValue("data/blob.bin").bytes)
                    assertEquals(tag, fileBytes.size.toLong(), entries.getValue("data/blob.bin").info.sizeBytes)
                    assertArrayEquals(tag, ByteArray(0), entries.getValue("data/empty").bytes)
                    assertArrayEquals(tag, fileBytes, entries.getValue("data/exact.raw").bytes)
                    assertArrayEquals(tag, streamBytes, entries.getValue("data/inexact.tar").bytes)
                    assertArrayEquals(tag, byteArrayOf(1, 2, 3), entries.getValue(longName).bytes)

                    val link = entries.getValue("data/link").info
                    assertEquals(tag, ArchiveEntryType.SYMLINK, link.type)
                    assertEquals(tag, "../lib/arm64", link.linkTarget)

                    val blob = entries.getValue("data/blob.bin").info.metadata
                    assertEquals(tag, 0x1B0, blob.mode)
                    assertEquals(tag, 0x1F9, entries.getValue("data").info.metadata.mode)
                    assertEquals(tag, mtime, blob.modifiedAtMillis)
                    if (format != ArchiveFormat.SEVEN_ZIP) {
                        assertEquals(tag, 10123, blob.uid)
                        assertEquals(tag, 20123, blob.gid)
                    }
                    if (format.storageKey.startsWith("tar")) {
                        assertEquals(tag, selinux, blob.seLinuxContext)
                        assertEquals(tag, selinux, entries.getValue("data").info.metadata.seLinuxContext)
                    } else {
                        assertNull(tag, blob.seLinuxContext)
                    }
                }
                assertTrue("$format left staging files", staging.listFiles().orEmpty().isEmpty())
            }
        }
    }

    @Test
    fun tarWritesGnuTarSelinuxPaxRecord() {
        val bytes = write(ArchiveFormat.TAR, 0)
        // pax record: "<len> RHT.security.selinux=<ctx>\n"
        val record = "RHT.security.selinux=$selinux\n"
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains(record))
        val entry = TarArchiveInputStream(ByteArrayInputStream(bytes)).use { tin ->
            generateSequence { tin.nextEntry }.first { it.name == "data/blob.bin" }
        }
        assertEquals(selinux, entry.getExtraPaxHeader("RHT.security.selinux"))
    }

    @Test
    fun tarReaderAcceptsSchilyXattrSpelling() {
        val sink = ByteArrayOutputStream()
        TarArchiveWriter.newTarStream(sink).use { tar ->
            val e = TarArchiveEntry("f")
            e.size = 1
            e.addPaxHeader("SCHILY.xattr.security.selinux", "u:object_r:system_file:s0\u0000")
            tar.putArchiveEntry(e)
            tar.write(byteArrayOf(9))
            tar.closeArchiveEntry()
        }
        val entries = read(ArchiveFormat.TAR, sink.toByteArray(), viaFile = false)
        assertEquals("u:object_r:system_file:s0", entries.getValue("f").info.metadata.seLinuxContext)
    }

    @Test
    fun tarHeaderEncodesSizesAbove8GiB() {
        val tenGiB = 10L shl 30
        val sink = ByteArrayOutputStream()
        // 512-byte blocks so headers reach the sink without writing the (fake) 10 GiB body.
        val tar = TarArchiveWriter.newTarStream(sink, blockSize = 512)
        val entry = TarArchiveEntry("huge.img").apply { size = tenGiB }
        TarArchiveWriter.applyMetadata(entry, EntryMetadata(mode = 0x1A4, uid = 0, gid = 0), 0x81A4)
        tar.putArchiveEntry(entry)
        tar.flush()
        val header = sink.toByteArray()
        assertTrue(String(header, Charsets.ISO_8859_1).contains(" size=$tenGiB\n"))
        val parsed = TarArchiveInputStream(ByteArrayInputStream(header)).nextEntry
        assertEquals("huge.img", parsed.name)
        assertEquals(tenGiB, parsed.size)
    }

    @Test
    fun zipMarksLargeEntriesAsZip64() {
        // A declared >4 GiB size must select Zip64 in the local header even on a plain stream.
        val sink = ByteArrayOutputStream()
        val zip = ZipArchiveOutputStream(sink).apply { setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded) }
        val entry = ZipArchiveEntry("big").apply { size = 5L shl 30 }
        zip.putArchiveEntry(entry)
        zip.flush()
        val local = sink.toByteArray()
        // Local file header: "PK\3\4", then "version needed to extract" = 45 (4.5, Zip64).
        assertTrue(local.size >= 30)
        assertEquals(0x04034b50, (local[0].toInt() and 0xFF) or ((local[1].toInt() and 0xFF) shl 8) or ((local[2].toInt() and 0xFF) shl 16) or ((local[3].toInt() and 0xFF) shl 24))
        assertEquals(45, local[4].toInt())
    }

    @Test
    fun writersRejectUnsafePaths() = runBlocking {
        for (format in ArchiveFormat.entries) {
            factory.writer(format, ByteArrayOutputStream(), 1).use { w ->
                for (bad in listOf("/abs", "../up", "a/../../b", "nul\u0000")) {
                    try {
                        w.write(SourceItem.File(bad, 0) { ByteArrayInputStream(ByteArray(0)) })
                        fail("$format accepted '$bad'")
                    } catch (e: IllegalArgumentException) {
                        assertTrue(e.message!!.contains("Unsafe archive path"))
                    }
                }
            }
        }
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun readersRejectTraversalEntries() = runBlocking {
        val tarSink = ByteArrayOutputStream()
        TarArchiveWriter.newTarStream(tarSink).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("ok"))
            tar.closeArchiveEntry()
            tar.putArchiveEntry(TarArchiveEntry("../../evil"))
            tar.closeArchiveEntry()
        }
        val zipSink = ByteArrayOutputStream()
        ZipArchiveOutputStream(zipSink).use { zip ->
            zip.putArchiveEntry(ZipArchiveEntry("../evil"))
            zip.closeArchiveEntry()
        }
        for ((format, bytes) in listOf(ArchiveFormat.TAR to tarSink.toByteArray(), ArchiveFormat.ZIP to zipSink.toByteArray())) {
            factory.reader(format, ArchiveReadSource.Streamed { ByteArrayInputStream(bytes) }).use { r ->
                try {
                    r.entries().forEach { it.stream.readBytes() }
                    fail("$format allowed traversal")
                } catch (e: ArchiveIoException) {
                    assertEquals(ArchiveErrorKind.UNSAFE_PATH, e.kind)
                }
            }
        }
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun sevenZipStagingIsDeletedOnFinishCloseAndFailure() = runBlocking {
        val ok = factory.writer(ArchiveFormat.SEVEN_ZIP, ByteArrayOutputStream(), 5) as SevenZipArchiveWriter
        assertTrue(ok.stagingFile.exists())
        ok.write(SourceItem.File("a", 1) { ByteArrayInputStream(byteArrayOf(1)) })
        ok.finish()
        assertFalse(ok.stagingFile.exists())
        ok.close()

        val abandoned = factory.writer(ArchiveFormat.SEVEN_ZIP, ByteArrayOutputStream(), 5) as SevenZipArchiveWriter
        abandoned.write(SourceItem.File("a", 1) { ByteArrayInputStream(byteArrayOf(1)) })
        abandoned.close()
        assertFalse(abandoned.stagingFile.exists())

        val failing = factory.writer(ArchiveFormat.SEVEN_ZIP, ByteArrayOutputStream(), 5) as SevenZipArchiveWriter
        try {
            failing.write(SourceItem.File("short", 10) { ByteArrayInputStream(byteArrayOf(1, 2)) })
            fail("size mismatch not detected")
        } catch (e: ArchiveIoException) {
            assertEquals(ArchiveErrorKind.SIZE_MISMATCH, e.kind)
        }
        failing.close()
        assertFalse(failing.stagingFile.exists())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun sizeMismatchIsDetectedInBothDirections() = runBlocking {
        for (format in listOf(ArchiveFormat.TAR, ArchiveFormat.ZIP, ArchiveFormat.RAW_COPY)) {
            for ((declared, actual) in listOf(10L to 5, 2L to 6)) {
                factory.writer(format, ByteArrayOutputStream(), 1).use { w ->
                    try {
                        w.write(SourceItem.File("f", declared) { ByteArrayInputStream(ByteArray(actual)) })
                        fail("$format accepted $actual bytes for declared $declared")
                    } catch (e: ArchiveIoException) {
                        assertEquals(ArchiveErrorKind.SIZE_MISMATCH, e.kind)
                    }
                }
            }
        }
    }

    @Test
    fun singleItemFormatsCopyExactlyOneByteItem() = runBlocking {
        for (format in listOf(ArchiveFormat.RAW_COPY, ArchiveFormat.DD_IMAGE)) {
            val sink = TrackingSink()
            factory.writer(format, sink, 3).use { w ->
                w.write(SourceItem.Stream("boot.img", null, sizeIsExact = false) { ByteArrayInputStream(streamBytes) })
                try {
                    w.write(SourceItem.File("second", 1) { ByteArrayInputStream(byteArrayOf(1)) })
                    fail("$format accepted a second item")
                } catch (e: IllegalArgumentException) {
                    assertEquals(ArchiveErrorKind.SINGLE_ITEM_ONLY, (e as ArchiveError).kind)
                    assertTrue(e.message!!.contains("exactly one item"))
                }
                w.finish()
            }
            assertArrayEquals(streamBytes, sink.toByteArray())
            assertFalse(sink.closed)

            factory.writer(format, ByteArrayOutputStream(), 0).use { w ->
                try {
                    w.write(SourceItem.Directory("d"))
                    fail("$format accepted a directory")
                } catch (e: ArchiveArgumentException) {
                    assertEquals(ArchiveErrorKind.SINGLE_ITEM_ONLY, e.kind)
                }
                try {
                    w.finish()
                    fail("$format finished empty")
                } catch (e: ArchiveArgumentException) {
                    assertEquals(ArchiveErrorKind.SINGLE_ITEM_ONLY, e.kind)
                }
            }
        }
    }

    @Test
    fun writerLifecycleIsEnforced() = runBlocking {
        val w = factory.writer(ArchiveFormat.TAR_ZST, ByteArrayOutputStream(), 3)
        w.finish()
        try {
            w.write(SourceItem.Directory("x"))
            fail("write after finish")
        } catch (_: ArchiveStateException) {
        }
        w.close()
        w.close()
    }

    @Test
    fun abandonedTarHasNoEofTrailer() = runBlocking {
        val sink = ByteArrayOutputStream()
        val w = factory.writer(ArchiveFormat.TAR, sink, 0)
        w.write(SourceItem.File("a", 1) { ByteArrayInputStream(byteArrayOf(7)) })
        w.close()
        // Only buffered data may have reached the sink; never the two zero EOF records + padding.
        assertTrue(sink.size() < 10240)
    }

    @Test
    fun rawFormatsCannotBeReadAsArchives() = runBlocking {
        try {
            factory.reader(ArchiveFormat.DD_IMAGE, ArchiveReadSource.Streamed { ByteArrayInputStream(ByteArray(0)) })
            fail("dd image opened as archive")
        } catch (e: ArchiveArgumentException) {
            assertEquals(ArchiveErrorKind.UNSUPPORTED_FORMAT, e.kind)
        }
    }

    @Test
    fun stagingRequirement() {
        assertEquals(500L, factory.localStagingBytes(ArchiveFormat.SEVEN_ZIP, 500, 20))
        assertEquals(20L, factory.localStagingBytes(ArchiveFormat.TAR_ZST, 500, 20))
        assertEquals(0L, factory.localStagingBytes(ArchiveFormat.DD_IMAGE, 500, 20))
    }

    @Test
    fun tarWithoutEofRecordIsReportedTruncated() = runBlocking {
        // Header + one data record, flushed with 512-byte blocks and never finished: no EOF records.
        val sink = ByteArrayOutputStream()
        val tar = TarArchiveWriter.newTarStream(sink, blockSize = 512)
        tar.putArchiveEntry(TarArchiveEntry("a").apply { size = 1 })
        tar.write(byteArrayOf(5))
        tar.closeArchiveEntry()
        tar.flush()
        val noTrailer = sink.toByteArray()
        factory.reader(ArchiveFormat.TAR, ArchiveReadSource.Streamed { ByteArrayInputStream(noTrailer) }).use { r ->
            try {
                r.entries().forEach { it.stream.readBytes() }
                fail("missing EOF record accepted")
            } catch (e: ArchiveIoException) {
                assertEquals(ArchiveErrorKind.TRUNCATED, e.kind)
            }
        }
    }

    @Test
    fun abandonedCompressedTarNeverReadsAsComplete() = runBlocking {
        for (format in listOf(ArchiveFormat.TAR_GZ, ArchiveFormat.TAR_XZ, ArchiveFormat.TAR_ZST, ArchiveFormat.TAR_BZ2)) {
            val sink = ByteArrayOutputStream()
            val w = factory.writer(format, sink, 1)
            w.write(SourceItem.File("a", fileBytes.size.toLong()) { ByteArrayInputStream(fileBytes) })
            w.close()
            try {
                factory.reader(format, ArchiveReadSource.Streamed { ByteArrayInputStream(sink.toByteArray()) }).use { r ->
                    r.entries().forEach { it.stream.readBytes() }
                }
                fail("$format abandoned archive read as complete")
            } catch (_: java.io.IOException) {
            }
        }
    }

    @Test
    fun tarSpecialFilesAreNotRegularFiles() {
        val sink = ByteArrayOutputStream()
        TarArchiveWriter.newTarStream(sink).use { tar ->
            for ((name, flag) in listOf("fifo" to org.apache.commons.compress.archivers.tar.TarConstants.LF_FIFO,
                    "chr" to org.apache.commons.compress.archivers.tar.TarConstants.LF_CHR,
                    "blk" to org.apache.commons.compress.archivers.tar.TarConstants.LF_BLK)) {
                tar.putArchiveEntry(TarArchiveEntry(name, flag))
                tar.closeArchiveEntry()
            }
        }
        val entries = read(ArchiveFormat.TAR, sink.toByteArray(), viaFile = false)
        assertEquals(setOf("fifo", "chr", "blk"), entries.keys)
        entries.values.forEach { assertEquals(it.info.path, ArchiveEntryType.OTHER, it.info.type) }
    }

    @Test
    fun zip64IsForcedNearTheLimitAndMixedModesStayReadable() {
        assertEquals(org.apache.commons.compress.archivers.zip.Zip64Mode.Always, ZipArchiveWriter.zip64ModeFor(0xFFFFFFFFL - 100_000))
        assertEquals(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded, ZipArchiveWriter.zip64ModeFor(1L shl 31))
        assertTrue(ZipArchiveWriter.deflateBound(1L shl 32) > (1L shl 32))

        // Same per-entry switching the writer does, on a non-seekable stream.
        val sink = ByteArrayOutputStream()
        ZipArchiveOutputStream(sink).use { zip ->
            for ((i, mode) in listOf(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded,
                    org.apache.commons.compress.archivers.zip.Zip64Mode.Always,
                    org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded).withIndex()) {
                zip.setUseZip64(mode)
                zip.putArchiveEntry(ZipArchiveEntry("e$i").apply { size = fileBytes.size.toLong() })
                zip.write(fileBytes)
                zip.closeArchiveEntry()
                zip.setUseZip64(org.apache.commons.compress.archivers.zip.Zip64Mode.AsNeeded)
            }
        }
        val bytes = sink.toByteArray()
        val viaCentral = read(ArchiveFormat.ZIP, bytes, viaFile = true)
        assertEquals(setOf("e0", "e1", "e2"), viaCentral.keys)
        viaCentral.values.forEach { assertArrayEquals(fileBytes, it.bytes) }
        org.apache.commons.compress.archivers.zip.ZipArchiveInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var n = 0
            while (zin.nextEntry != null) {
                assertArrayEquals(fileBytes, zin.readBytes())
                n++
            }
            assertEquals(3, n)
        }
    }

    @Test
    fun oversizedZipSymlinkTargetIsRefused() = runBlocking {
        val sink = ByteArrayOutputStream()
        ZipArchiveOutputStream(sink).use { zip ->
            val e = ZipArchiveEntry("link").apply { unixMode = 0xA1FF }
            zip.putArchiveEntry(e)
            zip.write(ByteArray(200_000) { 'a'.code.toByte() })
            zip.closeArchiveEntry()
        }
        factory.reader(ArchiveFormat.ZIP, ArchiveReadSource.Streamed { ByteArrayInputStream(sink.toByteArray()) }).use { r ->
            try {
                r.entries().toList()
                fail("huge symlink target accepted")
            } catch (e: ArchiveIoException) {
                assertEquals(ArchiveErrorKind.UNSAFE_PATH, e.kind)
            }
        }
    }
}
