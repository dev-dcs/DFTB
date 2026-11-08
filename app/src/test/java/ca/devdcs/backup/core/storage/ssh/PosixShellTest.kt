package ca.devdcs.backup.core.storage.ssh

import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosixShellTest {

    @Test
    fun `parses regular file stat line`() {
        val line = PosixShell.parseStatLine("1048576/1700000000/regular file//mnt/x/archive.tar.zst")!!
        assertEquals(1048576L, line.sizeBytes)
        assertEquals(1_700_000_000_000L, line.modifiedAtMillis)
        assertFalse(line.isDirectory)
        assertEquals("/mnt/x/archive.tar.zst", line.path)
        assertEquals("archive.tar.zst", line.name)
    }

    @Test
    fun `parses directory and names containing slashes-free special characters`() {
        val dir = PosixShell.parseStatLine("4096/1700000001/directory//mnt/x/my dir ' \$x")!!
        assertTrue(dir.isDirectory)
        assertEquals("my dir ' \$x", dir.name)
        val empty = PosixShell.parseStatLine("0/1700000002/regular empty file//b/e")!!
        assertFalse(empty.isDirectory)
        assertEquals(0L, empty.sizeBytes)
    }

    @Test
    fun `fractional mtime and garbage lines`() {
        assertEquals(5000L, PosixShell.parseStatLine("1/5.123/regular file//a")!!.modifiedAtMillis)
        assertNull(PosixShell.parseStatLine(""))
        assertNull(PosixShell.parseStatLine("stat: cannot stat"))
        assertNull(PosixShell.parseStatLine("x/1/regular file//a"))
    }

    @Test
    fun `parses posix df output`() {
        val out = listOf(
            "Filesystem     1024-blocks      Used Available Capacity Mounted on",
            "/dev/sda1        102400000  50000000  52400000      49% /srv/backup",
        )
        assertEquals(SpaceInfo(freeBytes = 52_400_000L * 1024, totalBytes = 102_400_000L * 1024), PosixShell.parseDf(out))
    }

    @Test
    fun `parses df with spaces in device and wrapped line`() {
        val spaced = listOf(
            "Filesystem 1K-blocks Used Available Use% Mounted on",
            "//nas/My Share 2000 500 1500 25% /mnt/my share",
        )
        assertEquals(SpaceInfo(1500L * 1024, 2000L * 1024), PosixShell.parseDf(spaced))
        val wrapped = listOf(
            "Filesystem           1K-blocks      Used Available Use% Mounted on",
            "/dev/mapper/very-long-volume-name",
            "                       1000       100       900  10% /",
        )
        assertEquals(SpaceInfo(900L * 1024, 1000L * 1024), PosixShell.parseDf(wrapped))
    }

    @Test
    fun `df garbage yields null`() {
        assertNull(PosixShell.parseDf(emptyList()))
        assertNull(PosixShell.parseDf(listOf("df: /x: No such file or directory")))
    }

    @Test
    fun `classifies common shell errors`() {
        assertEquals(StorageException.Reason.NO_SPACE, PosixShell.classifyError("cat: write error: No space left on device"))
        assertEquals(StorageException.Reason.PERMISSION, PosixShell.classifyError("mkdir: /x: Permission denied"))
        assertEquals(StorageException.Reason.PERMISSION, PosixShell.classifyError("Read-only file system"))
        assertEquals(StorageException.Reason.NOT_FOUND, PosixShell.classifyError("tail: /x: No such file or directory"))
        assertEquals(StorageException.Reason.NETWORK, PosixShell.classifyError("Stale file handle"))
        assertEquals(StorageException.Reason.TIMEOUT, PosixShell.classifyError("mount: Connection timed out"))
        assertEquals(StorageException.Reason.UNSUPPORTED, PosixShell.classifyError("mount: No such device"))
        assertEquals(StorageException.Reason.PROTOCOL, PosixShell.classifyError("weird"))
    }

    @Test
    fun `scripts quote hostile paths`() {
        val hostile = "/a/b'; rm -rf / #"
        val quoted = "'/a/b'\\''; rm -rf / #'"
        assertTrue(PosixShell.statScript(hostile).contains(quoted))
        assertTrue(PosixShell.mkdirsScript(hostile) == "mkdir -p $quoted")
        assertEquals("cat $quoted", PosixShell.readCommand(hostile, 0))
        assertEquals("tail -c +11 $quoted", PosixShell.readCommand(hostile, 10))
        assertTrue(PosixShell.deleteScript(hostile).contains("rm -f $quoted"))
        assertEquals("df -Pk $quoted", PosixShell.dfScript(hostile))
    }

    @Test
    fun `component paths stay under the root`() {
        assertEquals(listOf("/m/a", "/m/a/b"), PosixShell.componentPaths("/m", "/m/a/b", includeFinal = true))
        assertEquals(listOf("/m/a"), PosixShell.componentPaths("/m", "/m/a/b", includeFinal = false))
        assertEquals(emptyList<String>(), PosixShell.componentPaths("/m", "/m", includeFinal = true))
        assertNull(PosixShell.componentPaths("/m", "/data/system", includeFinal = true))
        assertNull(PosixShell.componentPaths("/m", "/mx/a", includeFinal = true))
    }

    @Test
    fun `symlink guard checks every component and refuses outside paths`() {
        val guard = PosixShell.symlinkGuard("/m", "/m/a b/c", includeFinal = true)
        assertTrue(guard.contains("for __p in '/m/a b' '/m/a b/c'; do if [ -L \"\$__p\" ]"))
        assertTrue(guard.contains("exit 1"))
        assertEquals("", PosixShell.symlinkGuard("/m", "/m", includeFinal = true))
        assertTrue(PosixShell.symlinkGuard("/m", "/etc/x", includeFinal = true).contains("outside the mount"))
        assertEquals(StorageException.Reason.PERMISSION, PosixShell.classifyError("Permission denied: symbolic link /m/a"))
        assertEquals(StorageException.Reason.PERMISSION, PosixShell.classifyError("rmdir: '/m/d': Directory not empty"))
    }
}
