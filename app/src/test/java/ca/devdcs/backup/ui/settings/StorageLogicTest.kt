package ca.devdcs.backup.ui.settings

import ca.devdcs.backup.core.logging.LogLevel
import ca.devdcs.backup.core.logging.LogLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZoneOffset

class StorageLogicTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val utc: ZoneId = ZoneOffset.UTC

    private val header = LogExportHeader(
        appName = "DFTB",
        appVersion = "0.6.76",
        versionCode = 676,
        buildType = "debug",
        packageName = "ca.devdcs.backup.debug",
        device = "Google Pixel 9",
        androidRelease = "17",
        apiLevel = 37,
        operatingMode = "root",
        // 2026-10-20 14:03:05 UTC
        exportedAtMillis = 1_792_504_985_000L,
    )

    // ---------------------------------------------------------------- log export

    @Test
    fun headerDescribesBuildDeviceAndMode() {
        val text = LogExportFormatter.format(emptyList(), header, utc)
        val lines = text.lines()
        assertEquals("# DFTB log export", lines[0])
        assertEquals("# Exported: 2026-10-20 14:03:05 +00:00", lines[1])
        assertEquals("# App: 0.6.76 (676, debug) ca.devdcs.backup.debug", lines[2])
        assertEquals("# Device: Google Pixel 9, Android 17 (API 37)", lines[3])
        assertEquals("# Operating mode: root", lines[4])
        assertEquals("# Lines: 0", lines[5])
    }

    @Test
    fun unconfiguredModeIsSpelledOut() {
        assertTrue(LogExportFormatter.format(emptyList(), header.copy(operatingMode = null), utc).contains("# Operating mode: not configured\n"))
    }

    @Test
    fun recordsUseLevelLetterTagAndMillisecondTimestamps() {
        val lines = listOf(
            LogLine(1_792_504_985_123L, LogLevel.INFO, "Backup", "Started"),
            LogLine(1_792_504_986_000L, LogLevel.ERROR, "Sftp", "Upload failed", throwable = "java.io.IOException: reset\n\tat a.b(C.kt:1)\n"),
        )
        val body = LogExportFormatter.format(lines, header, utc).substringAfter("\n\n")
        assertEquals(
            "2026-10-20 14:03:05.123 I/Backup: Started\n" +
                "2026-10-20 14:03:06.000 E/Sftp: Upload failed\n" +
                "    java.io.IOException: reset\n" +
                "    \tat a.b(C.kt:1)\n",
            body,
        )
    }

    @Test
    fun multiLineMessagesStayIndentedUnderTheirRecord() {
        val body = LogExportFormatter.format(listOf(LogLine(0L, LogLevel.WARN, "T", "first\nsecond\n")), header, utc).substringAfter("\n\n")
        assertEquals("1970-01-01 00:00:00.000 W/T: first\n    second\n", body)
    }

    @Test
    fun everyLevelHasADistinctLetter() {
        assertEquals(listOf('V', 'D', 'I', 'W', 'E'), LogLevel.entries.map { LogExportFormatter.levelLetter(it) })
    }

    @Test
    fun fileNameIsSortableAndSafe() {
        val name = LogExportFormatter.fileName(header.exportedAtMillis, utc)
        assertEquals("dftb-log-20261020-140305.txt", name)
        assertTrue(name.all { it.isLetterOrDigit() || it == '-' || it == '.' })
    }

    // ---------------------------------------------------------------- cache files

    @Test
    fun sizeCountsRegularFilesRecursively() {
        val root = temp.newFolder("cache")
        File(root, "a.bin").writeBytes(ByteArray(100))
        File(root, "nested/deeper").mkdirs()
        File(root, "nested/deeper/b.bin").writeBytes(ByteArray(250))
        assertEquals(350L, CacheFiles.sizeOf(root))
    }

    @Test
    fun missingDirectoryIsEmpty() {
        assertEquals(0L, CacheFiles.sizeOf(File(temp.root, "absent")))
        assertEquals(ClearOutcome(0L, 0), CacheFiles.clearContents(File(temp.root, "absent")))
    }

    @Test
    fun symlinksAreNeitherFollowedForSizeNorForDeletion() {
        val outside = temp.newFolder("outside")
        val precious = File(outside, "precious.bin").apply { writeBytes(ByteArray(1_000)) }
        val root = temp.newFolder("cache2")
        File(root, "own.bin").writeBytes(ByteArray(10))
        Files.createSymbolicLink(File(root, "link-to-outside").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "link-to-file").toPath(), precious.toPath())

        assertEquals(10L, CacheFiles.sizeOf(root))

        val outcome = CacheFiles.clearContents(root)
        assertEquals(0, outcome.failedEntries)
        assertEquals(10L, outcome.freedBytes)
        assertTrue("target outside the cache must survive", precious.isFile)
        assertEquals(1_000L, precious.length())
        assertTrue(root.isDirectory)
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test
    fun clearKeepsTheRootAndEmptiesNestedDirectories() {
        val root = temp.newFolder("staging")
        File(root, "x/y").mkdirs()
        File(root, "x/y/part.7z").writeBytes(ByteArray(64))
        File(root, "top.part").writeBytes(ByteArray(16))
        val outcome = CacheFiles.clearContents(root)
        assertEquals(ClearOutcome(80L, 0), outcome)
        assertTrue(root.isDirectory)
        assertFalse(File(root, "x").exists())
    }

    @Test
    fun aSymlinkedRootIsNotEntered() {
        val real = temp.newFolder("real")
        File(real, "keep.bin").writeBytes(ByteArray(5))
        val linkRoot = File(temp.root, "linked-root")
        Files.createSymbolicLink(linkRoot.toPath(), real.toPath())
        assertEquals(ClearOutcome(0L, 0), CacheFiles.clearContents(linkRoot))
        assertTrue(File(real, "keep.bin").isFile)
    }
}
