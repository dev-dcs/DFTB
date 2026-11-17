package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class RemotePathsTest {

    @Test
    fun `normalize drops empty and dot segments`() {
        assertEquals("a/b/c.tar", RemotePaths.normalize("/a//./b/c.tar/"))
        assertEquals("", RemotePaths.normalize("/"))
        assertEquals("", RemotePaths.normalize(""))
        // Backslash is a legal filename character outside SMB.
        assertEquals("a\\b", RemotePaths.normalize("a\\b"))
    }

    @Test
    fun `dot-dot is rejected as misconfigured`() {
        try {
            RemotePaths.normalize("a/../../etc/passwd")
            fail("expected StorageException")
        } catch (e: StorageException) {
            assertEquals(StorageException.Reason.MISCONFIGURED, e.reason)
        }
    }

    @Test
    fun `join keeps absolute bases absolute`() {
        assertEquals("/srv/backup/x/y", RemotePaths.join("/srv/backup/", "x/y"))
        assertEquals("/srv/backup", RemotePaths.join("/srv/backup", ""))
        assertEquals("/", RemotePaths.join("/", ""))
        assertEquals("/f", RemotePaths.join("/", "/f"))
    }

    @Test
    fun `join with relative or empty base stays relative`() {
        assertEquals("backups/f", RemotePaths.join("backups", "f"))
        assertEquals(".", RemotePaths.join("", ""))
        assertEquals("f", RemotePaths.join("", "f"))
    }

    @Test
    fun `parent name and child`() {
        assertEquals("a/b", RemotePaths.parent("a/b/c"))
        assertEquals("", RemotePaths.parent("c"))
        assertEquals("c", RemotePaths.name("a/b/c"))
        assertEquals("", RemotePaths.name(""))
        assertEquals("x", RemotePaths.child("", "x"))
        assertEquals("a/x", RemotePaths.child("/a/", "x"))
    }
}
