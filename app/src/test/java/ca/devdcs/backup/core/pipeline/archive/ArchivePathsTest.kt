package ca.devdcs.backup.core.pipeline.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ArchivePathsTest {

    @Test
    fun writeAcceptsRelativeAndStripsTrailingSlash() {
        assertEquals("data/app/x", ArchivePaths.requireSafeForWrite("data/app/x"))
        assertEquals("dir", ArchivePaths.requireSafeForWrite("dir/"))
        assertEquals("a/..b/c..", ArchivePaths.requireSafeForWrite("a/..b/c.."))
    }

    @Test
    fun writeRejectsUnsafe() {
        for (bad in listOf("", "/", "/etc/passwd", "../x", "a/../b", "a/..", "a\u0000b", "a//b", "./a", "a/./b")) {
            try {
                ArchivePaths.requireSafeForWrite(bad)
                fail("accepted '$bad'")
            } catch (e: IllegalArgumentException) {
                assertEquals(ArchiveErrorKind.UNSAFE_PATH, (e as ArchiveError).kind)
            }
        }
    }

    @Test
    fun readNormalizesHarmlessForms() {
        assertEquals("a/b", ArchivePaths.normalizeForRead("./a/./b"))
        assertEquals("a/b", ArchivePaths.normalizeForRead("a//b/"))
        assertNull(ArchivePaths.normalizeForRead("./"))
    }

    @Test
    fun readRejectsTraversal() {
        for (bad in listOf("../evil", "a/../../evil", "/abs", "C:\\win", "a\\..\\b", "x\u0000y")) {
            try {
                ArchivePaths.normalizeForRead(bad)
                fail("accepted '$bad'")
            } catch (e: ArchiveIoException) {
                assertEquals(ArchiveErrorKind.UNSAFE_PATH, e.kind)
            }
        }
    }
}
