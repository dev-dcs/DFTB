package ca.devdcs.backup.core.discovery.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExclusionMatcherTest {

    private val m = ExclusionMatcher.DEFAULT

    @Test
    fun `excludes default directories case-insensitively`() {
        assertTrue(m.excludesDirectory("DCIM/.thumbnails"))
        assertTrue(m.excludesDirectory(".Trash-1000"))
        assertTrue(m.excludesDirectory("app/Cache"))
        assertTrue(m.excludesDirectory("x/code_cache/y"))
        assertTrue(m.excludesDirectory("Android/data"))
        assertTrue(m.excludesDirectory("Android/data/com.foo/files"))
        assertFalse(m.excludesDirectory("Android/media"))
        assertFalse(m.excludesDirectory("data"))
        assertFalse(m.excludesDirectory("DCIM/Camera"))
        assertFalse(m.excludesDirectory(""))
    }

    @Test
    fun `files are excluded by ancestors and trash prefix`() {
        assertTrue(m.excludesFile("DCIM/.thumbnails/123.jpg"))
        assertTrue(m.excludesFile("DCIM/.trashed-1690000000-IMG.jpg"))
        assertTrue(m.excludesFile("Android/data/com.x/a.db"))
        assertFalse(m.excludesFile("DCIM/cache.jpg"))
        assertFalse(m.excludesFile("Download/data"))
    }

    @Test
    fun `root inside Android data is explicitly targeted`() {
        // Paths are relative to the root, so a root of .../Android/data/com.x never sees the pair.
        assertFalse(m.excludesFile("files/save.dat"))
    }

    @Test
    fun `disabled matcher excludes nothing`() {
        assertFalse(ExclusionMatcher.NONE.excludesDirectory(".thumbnails"))
        assertFalse(ExclusionMatcher.of(false).excludesFile("Android/data/x"))
        assertTrue(ExclusionMatcher.of(true).enabled)
    }
}
