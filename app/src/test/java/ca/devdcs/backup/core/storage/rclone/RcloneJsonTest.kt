package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RcloneJsonTest {

    private val lsjson = """
        [
        {"Path":"photos","Name":"photos","Size":-1,"MimeType":"inode/directory","ModTime":"2024-03-01T10:00:00Z","IsDir":true},
        {"Path":"backup.tar.zst","Name":"backup.tar.zst","Size":1048576,"MimeType":"application/zstd","ModTime":"2017-05-31T16:15:57.034468261+01:00","IsDir":false,"Hashes":{"md5":"x"}},
        {"Path":"weird","Name":"weird","Size":0,"ModTime":"not a time","IsDir":false}
        ]
    """.trimIndent()

    @Test
    fun `lsjson entries map to RemoteEntry relative to the base`() {
        val entries = RcloneJson.parseList(lsjson, "/daily/")
        assertEquals(3, entries.size)
        val dir = entries[0]
        assertTrue(dir.isDirectory)
        assertEquals("daily/photos", dir.path)
        assertNull(dir.sizeBytes)
        assertEquals(1709287200000L, dir.modifiedAtMillis)

        val file = entries[1]
        assertFalse(file.isDirectory)
        assertEquals("backup.tar.zst", file.name)
        assertEquals("daily/backup.tar.zst", file.path)
        assertEquals(1048576L, file.sizeBytes)
        assertEquals(1496243757034L, file.modifiedAtMillis)

        assertNull(entries[2].modifiedAtMillis)
        assertEquals(0L, entries[2].sizeBytes)
    }

    @Test
    fun `lsjson at the base uses bare names`() {
        assertEquals("photos", RcloneJson.parseList(lsjson, "").first().path)
        assertTrue(RcloneJson.parseList("[]", "x").isEmpty())
    }

    @Test
    fun `stat parses a single object`() {
        val entry = RcloneJson.parseStat(
            """{"Path":"a/b.bin","Name":"b.bin","Size":42,"ModTime":"2020-01-01T00:00:00Z","IsDir":false}""",
            "a/b.bin",
        )
        assertEquals("a/b.bin", entry.path)
        assertEquals("b.bin", entry.name)
        assertEquals(42L, entry.sizeBytes)
    }

    @Test
    fun `about with all fields`() {
        val info = RcloneJson.parseAbout("""{"total":1000,"used":400,"free":550,"trashed":10}""")
        assertEquals(550L, info.freeBytes)
        assertEquals(1000L, info.totalBytes)
    }

    @Test
    fun `about derives free from total and used`() {
        assertEquals(600L, RcloneJson.parseAbout("""{"total":1000,"used":400}""").freeBytes)
        val onlyUsed = RcloneJson.parseAbout("""{"used":400}""")
        assertNull(onlyUsed.freeBytes)
        assertNull(onlyUsed.totalBytes)
    }

    @Test
    fun `malformed output is a PROTOCOL failure`() {
        for (bad in listOf("not json", "{\"a\":1}", "")) {
            try {
                RcloneJson.parseList(bad, "")
                fail("expected failure for '$bad'")
            } catch (e: StorageException) {
                assertEquals(StorageException.Reason.PROTOCOL, e.reason)
            }
        }
    }

    @Test
    fun `paths are normalized and dot-dot is rejected`() {
        assertEquals("a/b", RclonePaths.normalize("//a/./b/"))
        assertEquals("", RclonePaths.normalize("/"))
        assertEquals("bucket/base/x/y", RclonePaths.join("bucket/base/", "/x/y"))
        assertEquals("x", RclonePaths.join("", "x"))
        try {
            RclonePaths.normalize("a/../../etc")
            fail()
        } catch (e: StorageException) {
            assertEquals(StorageException.Reason.MISCONFIGURED, e.reason)
        }
    }
}
