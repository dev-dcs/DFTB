package ca.devdcs.backup.core.restore.files

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RestorePathsAndScriptsTest {

    @Test
    fun labelsMirrorTheBackupAllocation() {
        val labels = RestorePaths.labels(listOf("/storage/emulated/0/DCIM", "/sdcard/Camera", "/mnt/usb/DCIM"))
        assertEquals("/storage/emulated/0/DCIM", labels["DCIM"])
        assertEquals("/sdcard/Camera", labels["Camera"])
        assertEquals("/mnt/usb/DCIM", labels["DCIM-2"])
    }

    @Test
    fun originMapsFilesBackToTheirRoot() {
        val labels = RestorePaths.labels(listOf("/storage/emulated/0/DCIM"))
        assertEquals(RestorePaths.Origin("/storage/emulated/0/DCIM", "Camera/a.jpg"), RestorePaths.origin("files/DCIM/Camera/a.jpg", labels))
        assertNull(RestorePaths.origin("files/Unknown/a.jpg", labels))
        assertNull(RestorePaths.origin("apps/com.x/base.apk", labels))
    }

    @Test
    fun selectionIsSegmentWise() {
        assertTrue(RestorePaths.selected("files/DCIM/a.jpg", setOf("files/DCIM")))
        assertTrue(RestorePaths.selected("files/DCIM", setOf("files/DCIM/")))
        assertFalse(RestorePaths.selected("files/DCIMX/a.jpg", setOf("files/DCIM")))
        assertTrue(RestorePaths.selected("anything", null))
        assertEquals("DCIM/a.jpg", RestorePaths.inChosenFolder("files/DCIM/a.jpg"))
        assertEquals("data", RestorePaths.twrpVolumeFolder("data.ext4.win001"))
    }

    @Test
    fun scriptsQuoteEveryPath() {
        val evil = "/data/it's \$(rm -rf /)"
        val script = RestoreScripts.commitScript("$evil.part", evil, replace = true)
        assertTrue(script.contains("'/data/it'\\''s \$(rm -rf /)'"))
        assertFalse(script.contains(" $evil "))
        assertTrue(RestoreScripts.symlinkScript("/a b", "-target").startsWith("ln -s -- '-target' '/a b'"))
    }

    @Test
    fun metadataScriptNeverChmodsOrTouchesSymlinks() {
        val ops = listOf(
            MetadataOp("/d/file", TargetNodeType.FILE, 1_700_000_000_000L, 0x1A4, 10100, 10100, "u:object_r:app_data_file:s0"),
            MetadataOp("/d/link", TargetNodeType.SYMLINK, 1_700_000_000_000L, 0x1FF, 10100, 10100, null),
        )
        val script = RestoreScripts.metadataScript(ops, ownership = true, mtime = true)
        assertTrue(script.contains("chown -h 10100:10100 '/d/file'"))
        assertTrue(script.contains("[ ! -L '/d/file' ] && chmod 644 '/d/file'"))
        assertTrue(script.contains("chcon -h 'u:object_r:app_data_file:s0' '/d/file'"))
        assertTrue(script.contains("[ ! -L '/d/file' ] && touch -h -c -m -t 202311142213.20 '/d/file'"))
        assertTrue(script.contains("chown -h 10100:10100 '/d/link'"))
        assertFalse(script.contains("chmod 777 '/d/link'"))
        assertFalse(script.contains("202311142213.20 '/d/link'"))
        // Every chmod in the script is guarded against a path that became a symlink.
        assertTrue(Regex("chmod").findAll(script).count() == Regex("\\[ ! -L '[^']*' \\] && chmod").findAll(script).count())
        assertEquals(listOf(1), RestoreScripts.parseFailures(listOf("noise", "${RestoreScripts.FAIL_MARKER} 1")))
        val unprivileged = RestoreScripts.metadataScript(ops, ownership = false, mtime = true)
        assertFalse(unprivileged.contains("chown"))
    }

    @Test
    fun lstatParsing() {
        assertEquals(TargetNode(TargetNodeType.FILE, 12L), RestoreScripts.parseLstat(listOf("F 12")))
        assertEquals(TargetNodeType.SYMLINK, RestoreScripts.parseLstat(listOf("L"))?.type)
        assertNull(RestoreScripts.parseLstat(listOf("N")))
    }

    @Test
    fun twrpVolumesWithAbsoluteNamesAreReadSafely() {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            val dir = TarArchiveEntry("/data/app/", TarArchiveEntry.LF_DIR, true)
            tar.putArchiveEntry(dir)
            tar.closeArchiveEntry()
            val body = "apk".toByteArray()
            val file = TarArchiveEntry("/data/app/base.apk", true)
            file.size = body.size.toLong()
            tar.putArchiveEntry(file)
            tar.write(body)
            tar.closeArchiveEntry()
            val link = TarArchiveEntry("/data/app/copy.apk", TarArchiveEntry.LF_LINK, true)
            link.linkName = "/data/app/base.apk"
            tar.putArchiveEntry(link)
            tar.closeArchiveEntry()
        }
        val entries = TwrpTarEntries.entries(ByteArrayInputStream(out.toByteArray())).map { it.info }.toList()
        assertEquals(listOf("data/app", "data/app/base.apk", "data/app/copy.apk"), entries.map { it.path })
        assertEquals("data/app/base.apk", entries[2].linkTarget)
        assertTrue(TwrpTarEntries.isTarVolumeName("data.ext4.win"))
        assertTrue(TwrpTarEntries.isTarVolumeName("system.ext4.win001"))
        assertFalse(TwrpTarEntries.isTarVolumeName("boot.emmc.win"))
        assertFalse(TwrpTarEntries.isTarVolumeName("data.ext4.win.sha2"))
    }

    @Test
    fun twrpVolumeWithTraversalIsRefused() {
        val out = ByteArrayOutputStream()
        TarArchiveOutputStream(out).use { tar ->
            val file = TarArchiveEntry("/data/../../etc/passwd", true)
            file.size = 1
            tar.putArchiveEntry(file)
            tar.write(byteArrayOf(1))
            tar.closeArchiveEntry()
        }
        try {
            TwrpTarEntries.entries(ByteArrayInputStream(out.toByteArray())).toList()
            fail("Expected an unsafe path refusal")
        } catch (e: ca.devdcs.backup.core.pipeline.archive.ArchiveIoException) {
            assertEquals(ca.devdcs.backup.core.pipeline.archive.ArchiveErrorKind.UNSAFE_PATH, e.kind)
        }
    }
}
