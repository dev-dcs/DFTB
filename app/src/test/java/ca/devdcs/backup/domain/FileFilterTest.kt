package ca.devdcs.backup.domain

import ca.devdcs.backup.domain.model.FileFilter
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileFilterTest {

    @Test
    fun `empty filter matches everything`() {
        assertTrue(FileFilter.NONE.isEmpty)
        assertTrue(FileFilter.NONE.matches("any/path.bin", 0, 0))
        assertTrue(FileFilter.NONE.matches("", -1, Long.MIN_VALUE))
    }

    @Test
    fun `include extensions are case and dot insensitive`() {
        val filter = FileFilter(includeExtensions = setOf(".JPG", "png"))
        assertTrue(filter.matches("DCIM/IMG_001.jpg", 10, 0))
        assertTrue(filter.matches("DCIM/IMG_001.JPG", 10, 0))
        assertTrue(filter.matches("a/b/c.PnG", 10, 0))
        assertFalse(filter.matches("a/b/c.gif", 10, 0))
    }

    @Test
    fun `include extensions reject files without an extension`() {
        val filter = FileFilter(includeExtensions = setOf("txt"))
        assertFalse(filter.matches("Documents/README", 1, 0))
        assertFalse(filter.matches("Documents/.hidden", 1, 0))
        assertFalse(filter.matches("Documents/trailingdot.", 1, 0))
    }

    @Test
    fun `exclude extensions win over include`() {
        val filter = FileFilter(includeExtensions = setOf("jpg", "tmp"), excludeExtensions = setOf("TMP"))
        assertTrue(filter.matches("x.jpg", 1, 0))
        assertFalse(filter.matches("x.tmp", 1, 0))
    }

    @Test
    fun `dotfiles without extension are not excluded by extension rules`() {
        val filter = FileFilter(excludeExtensions = setOf("bashrc"))
        assertTrue(filter.matches("home/.bashrc", 1, 0))
    }

    @Test
    fun `only the last path segment is considered for the extension`() {
        val filter = FileFilter(includeExtensions = setOf("txt"))
        assertFalse(filter.matches("notes.txt/image.png", 1, 0))
        assertTrue(filter.matches("archive.tar.gz/readme.txt", 1, 0))
        assertEquals("gz", FileFilter.extensionOf("backups/data.tar.gz"))
        assertEquals("", FileFilter.extensionOf("backups/"))
    }

    @Test
    fun `size bounds are inclusive`() {
        val filter = FileFilter(minSizeBytes = 100, maxSizeBytes = 200)
        assertFalse(filter.matches("f", 99, 0))
        assertTrue(filter.matches("f", 100, 0))
        assertTrue(filter.matches("f", 200, 0))
        assertFalse(filter.matches("f", 201, 0))
    }

    @Test
    fun `modified bounds are exclusive`() {
        val filter = FileFilter(modifiedAfterEpochMillis = 1_000, modifiedBeforeEpochMillis = 2_000)
        assertFalse(filter.matches("f", 0, 1_000))
        assertTrue(filter.matches("f", 0, 1_001))
        assertTrue(filter.matches("f", 0, 1_999))
        assertFalse(filter.matches("f", 0, 2_000))
    }

    @Test
    fun `complementary modified filters never overlap`() {
        val older = FileFilter(modifiedBeforeEpochMillis = 5_000)
        val newer = FileFilter(modifiedAfterEpochMillis = 5_000)
        for (ts in longArrayOf(4_999, 5_000, 5_001)) {
            val both = older.matches("f", 0, ts) && newer.matches("f", 0, ts)
            assertFalse("both matched at $ts", both)
        }
        assertFalse(older.matches("f", 0, 5_000))
        assertFalse(newer.matches("f", 0, 5_000))
    }

    @Test
    fun `include regex must match somewhere in the relative path`() {
        val filter = FileFilter(includeRegex = "^WhatsApp/Media/")
        assertTrue(filter.matches("WhatsApp/Media/WhatsApp Images/a.jpg", 1, 0))
        assertFalse(filter.matches("Download/WhatsApp/Media/a.jpg", 1, 0))
        val anywhere = FileFilter(includeRegex = "Media")
        assertTrue(anywhere.matches("Download/WhatsApp/Media/a.jpg", 1, 0))
    }

    @Test
    fun `exclude regex rejects matches`() {
        val filter = FileFilter(excludeRegex = "(^|/)\\.thumbnails(/|$)|\\.nomedia$")
        assertFalse(filter.matches("DCIM/.thumbnails/1.jpg", 1, 0))
        assertFalse(filter.matches("Pictures/.nomedia", 1, 0))
        assertTrue(filter.matches("DCIM/Camera/1.jpg", 1, 0))
    }

    @Test
    fun `blank regex is treated as absent`() {
        val filter = FileFilter(includeRegex = "", excludeRegex = "")
        assertTrue(filter.isEmpty)
        assertTrue(filter.matches("anything", 1, 0))
    }

    @Test
    fun `all criteria are ANDed`() {
        val filter = FileFilter(
            includeExtensions = setOf("mp4"),
            minSizeBytes = 1_000,
            modifiedAfterEpochMillis = 10,
            includeRegex = "^Movies/",
            excludeRegex = "trailer",
        )
        assertTrue(filter.matches("Movies/film.mp4", 5_000, 11))
        assertFalse(filter.matches("Movies/film.mkv", 5_000, 11))
        assertFalse(filter.matches("Movies/film.mp4", 999, 11))
        assertFalse(filter.matches("Movies/film.mp4", 5_000, 10))
        assertFalse(filter.matches("Other/film.mp4", 5_000, 11))
        assertFalse(filter.matches("Movies/trailer.mp4", 5_000, 11))
    }

    @Test
    fun `invalid regex is reported and throws on compile`() {
        val filter = FileFilter(includeRegex = "([unclosed")
        assertNotNull(filter.regexProblem())
        assertNull(FileFilter(includeRegex = "ok.*").regexProblem())
        var thrown = false
        try {
            filter.matches("x", 0, 0)
        } catch (error: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `compiled filter gives the same answers as matches`() {
        val filter = FileFilter(includeExtensions = setOf("jpg"), maxSizeBytes = 50, excludeRegex = "tmp")
        val compiled = filter.compile()
        val cases = listOf(Triple("a.jpg", 10L, 0L), Triple("a.jpg", 60L, 0L), Triple("tmp/a.jpg", 10L, 0L), Triple("a.png", 10L, 0L))
        for ((path, size, mtime) in cases) {
            assertEquals(path, filter.matches(path, size, mtime), compiled.matches(path, size, mtime))
        }
    }

    @Test
    fun `filter survives a json round trip`() {
        val json = Json { encodeDefaults = true }
        val original = FileFilter(
            includeExtensions = setOf("jpg"),
            excludeExtensions = setOf("tmp"),
            minSizeBytes = 1,
            maxSizeBytes = 2,
            modifiedAfterEpochMillis = 3,
            modifiedBeforeEpochMillis = 4,
            includeRegex = "^a",
            excludeRegex = "b$",
        )
        assertEquals(original, json.decodeFromString<FileFilter>(json.encodeToString(original)))
    }
}
