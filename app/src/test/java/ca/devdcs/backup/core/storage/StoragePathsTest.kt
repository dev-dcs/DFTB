package ca.devdcs.backup.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoragePathsTest {

    @Test
    fun `normalize strips separators dots and resolves parents`() {
        assertEquals("", StoragePaths.normalize(""))
        assertEquals("", StoragePaths.normalize("/"))
        assertEquals("", StoragePaths.normalize("./."))
        assertEquals("a/b", StoragePaths.normalize("/a//b/"))
        assertEquals("a/c", StoragePaths.normalize("a/./b/../c"))
        assertEquals("", StoragePaths.normalize("a/.."))
    }

    @Test
    fun `escaping the base is rejected`() {
        for (bad in listOf("..", "../x", "a/../../x", "/../etc/passwd")) {
            try {
                StoragePaths.normalize(bad)
                fail("expected rejection of $bad")
            } catch (e: StorageException) {
                assertEquals(StorageException.Reason.MISCONFIGURED, e.reason)
            }
        }
    }

    @Test
    fun `nul characters are rejected`() {
        try {
            StoragePaths.normalize("a\u0000b")
            fail("expected rejection")
        } catch (e: StorageException) {
            assertEquals(StorageException.Reason.MISCONFIGURED, e.reason)
        }
    }

    @Test
    fun `parent name and join`() {
        assertEquals("a/b", StoragePaths.parent("a/b/c.tar"))
        assertEquals("", StoragePaths.parent("c.tar"))
        assertEquals("", StoragePaths.parent(""))
        assertEquals("c.tar", StoragePaths.name("/a/b/c.tar/"))
        assertEquals("", StoragePaths.name(""))
        assertEquals("a/b", StoragePaths.join("a", "b"))
        assertEquals("b", StoragePaths.join("", "/b"))
        assertEquals("x", StoragePaths.join("a", "../x"))
    }

    @Test(expected = StorageException::class)
    fun `join cannot escape`() {
        StoragePaths.join("a", "../../x")
    }

    @Test
    fun `isWithin compares whole segments`() {
        assertTrue(StoragePaths.isWithin("/data/app", "/data/app"))
        assertTrue(StoragePaths.isWithin("/data/app/", "/data/app/x/y"))
        assertFalse(StoragePaths.isWithin("/data/app", "/data/app2"))
        assertFalse(StoragePaths.isWithin("/data/app", "/data"))
        assertFalse(StoragePaths.isWithin("/data/app", "/etc/passwd"))
        assertTrue(StoragePaths.isWithin("/", "/anything"))
    }
}
