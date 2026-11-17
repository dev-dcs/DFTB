package ca.devdcs.backup.core.pipeline.source

import ca.devdcs.backup.core.pipeline.layout.InventoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceScriptsTest {

    @Test
    fun `command quotes every token`() {
        assertEquals("'cat' '--' 'a b'\\''c'", PrivilegedStreamScripts.command(listOf("cat", "--", "a b'c")))
        assertEquals("cat -- '/sdcard/\$(rm -rf x)'", PrivilegedStreamScripts.catFile("/sdcard/\$(rm -rf x)"))
    }

    @Test
    fun `fifo helper writes stdout to the data pipe and status after closing it`() {
        val script = PrivilegedStreamScripts.fifoHelper("dd if='/dev/x'", "/c/d p", "/c/s")
        assertTrue(script.startsWith("exec 3>'/c/d p' || exit 1; "))
        assertTrue(script.contains("{ dd if='/dev/x' ; } 2>&1 >&3 3>&-"))
        assertTrue(script.indexOf("exec 3>&-;") < script.indexOf("> '/c/s'"))
        assertTrue(script.contains("tail -c ${PrivilegedStreamScripts.MAX_STDERR_CHARS}"))
    }

    @Test
    fun `detached launch prints the pid and quotes the helper as one argument`() {
        val launch = PrivilegedStreamScripts.detachedLaunch("echo 'hi'")
        assertTrue(launch.contains("setsid sh -c 'echo '\\''hi'\\''' </dev/null >/dev/null 2>&1 &"))
        assertTrue(launch.endsWith("echo \"\$!\""))
    }

    @Test
    fun `pid and status parsing`() {
        assertEquals(1234, PrivilegedStreamScripts.parsePid(listOf("noise", " 1234 ")))
        assertNull(PrivilegedStreamScripts.parsePid(listOf("x")))
        assertNull(PrivilegedStreamScripts.parsePid(listOf("1")))
        assertEquals(0 to "", PrivilegedStreamScripts.parseStatus("0\n\n"))
        assertEquals(1 to "dd: read error: I/O error", PrivilegedStreamScripts.parseStatus("1\ndd: read error: I/O error\n"))
        assertEquals(-1 to "", PrivilegedStreamScripts.parseStatus(""))
        assertEquals(-1, PrivilegedStreamScripts.parseStatus("garbage").first)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `kill refuses init and invalid pids`() {
        PrivilegedStreamScripts.killScript(1)
    }

    @Test
    fun `kill targets the process group then the pid`() {
        assertEquals("kill -9 -42 2>/dev/null || kill -9 42 2>/dev/null; true", PrivilegedStreamScripts.killScript(42))
    }

    @Test
    fun `base64 pipeline keeps the command exit status`() {
        val s = PrivilegedStreamScripts.base64Pipeline("tar -cf - x")
        assertTrue(s.startsWith("set -o pipefail; { tar -cf - x ; } | base64 -w "))
        assertEquals(0, PrivilegedStreamScripts.BASE64_LINE_WIDTH % 4)
    }

    @Test
    fun `partition image refuses on size change and never uses noerror`() {
        val s = PartitionScripts.imageScript("/dev/block/by-name/boot_a", 67108864)
        assertTrue(s.contains("blockdev --getsize64 '/dev/block/by-name/boot_a'"))
        assertTrue(s.contains("!= '67108864'"))
        assertTrue(s.endsWith("dd if='/dev/block/by-name/boot_a' bs=4194304"))
        assertFalse(s.contains("noerror"))
        assertEquals("dd if='/dev/sda' bs=4194304", PartitionScripts.imageScript("/dev/sda", null))
    }

    @Test
    fun `size probe parsing`() {
        val sizes = PartitionScripts.parseSizeProbe(listOf("4096 /dev/block/a", "-1 /dev/block/b", "x /dev/c", "8192 /dev/with space"))
        assertEquals(mapOf("/dev/block/a" to 4096L, "/dev/with space" to 8192L), sizes)
        assertTrue(PartitionScripts.sizeProbeScript(listOf("/dev/a")).contains("blockdev --getsize64 '/dev/a'"))
    }

    @Test
    fun `app tar uses toybox flags and excludes caches`() {
        val s = AppDataScripts.tarScript("/data/data", listOf("com.x"), listOf("com.x/cache", "com.x/code_cache"), selinux = false)
        assertEquals("'tar' '-c' '-f' '-' '--numeric-owner' '--exclude=com.x/cache' '--exclude=com.x/code_cache' '-C' '/data/data' 'com.x'", s)
        assertTrue(AppDataScripts.tarScript("/p", listOf("m"), emptyList(), selinux = true).contains("'--selinux'"))
    }

    @Test
    fun `stat listing prunes and parses`() {
        val s = AppDataScripts.statListingScript(listOf("/data/data/a"), listOf("/data/data/a/cache"))
        assertEquals("find '/data/data/a' \\( -path '/data/data/a/cache' \\) -prune -o -exec stat -c '%a %u %g %C %n' {} +", s)
        assertEquals(
            AppDataScripts.StatLine("771", 10123, 10123, "u:object_r:app_data_file:s0:c123,c256", "/data/data/a/my file"),
            AppDataScripts.parseStatLine("771 10123 10123 u:object_r:app_data_file:s0:c123,c256 /data/data/a/my file"),
        )
        assertNull(AppDataScripts.parseStatLine("continuation of a name with newline"))
        assertNull(AppDataScripts.parseStatLine("7x1 1 1 ctx /p")?.mode)
        assertNull(AppDataScripts.parseStatLine("771 1 1 ? /p")?.context)
        assertEquals(AppDataScripts.OwnerLine(1000, 1000, "u:r:x:s0", "/data/user_de/0/a"), AppDataScripts.parseOwnerLine("1000 1000 u:r:x:s0 /data/user_de/0/a"))
    }

    @Test
    fun `du probe and parsing`() {
        assertTrue(AppDataScripts.duScript(listOf("/data/data/a b")).contains("for __p in '/data/data/a b'; do"))
        assertEquals(
            mapOf("/data/data/a" to 2048L, "/data/media/0/Android/data/x y" to 1024L),
            AppDataScripts.parseDu(listOf("2\t/data/data/a", "1 /data/media/0/Android/data/x y", "du: cannot read", "")),
        )
    }

    @Test
    fun `stable hash is deterministic and order sensitive only to input`() {
        assertEquals(stableHash(listOf("a", "b")), stableHash(listOf("a", "b")))
        assertTrue(stableHash(listOf("a", "b")) != stableHash(listOf("ab")))
        assertEquals(16, stableHash(emptyList()).length)
    }

    @Test
    fun `existing dirs probe quotes paths`() {
        assertEquals("true", AppDataScripts.existingDirsScript(emptyList()))
        assertTrue(AppDataScripts.existingDirsScript(listOf("/a b")).startsWith("for __p in '/a b'; do"))
    }

    @Test
    fun `nandroid inventory and tar volume`() {
        val s = NandroidScripts.inventoryScript("/data", listOf("/data/media"))
        assertEquals("find '/data' -xdev \\( -path '/data/media' \\) -prune -o -exec stat -c '%f %s %n' {} +", s)
        assertEquals(InventoryType.FILE, NandroidScripts.parseInventoryLine("81a4 12 /data/x y")?.type)
        assertEquals("/data/x y", NandroidScripts.parseInventoryLine("81a4 12 /data/x y")?.path)
        assertEquals(InventoryType.DIRECTORY, NandroidScripts.parseInventoryLine("41f9 3452 /data/d")?.type)
        assertEquals(InventoryType.SYMLINK, NandroidScripts.parseInventoryLine("a1ff 7 /data/l")?.type)
        assertEquals(InventoryType.FIFO, NandroidScripts.parseInventoryLine("11b6 0 /data/p")?.type)
        assertEquals(InventoryType.OTHER, NandroidScripts.parseInventoryLine("c1ff 0 /data/sock")?.type)
        assertNull(NandroidScripts.parseInventoryLine("tail of a name"))
        val vol = NandroidScripts.tarVolumeScript("/", "/c/l.list", "/c/l.list.live", selinux = true)
        assertTrue(vol.startsWith("cd '/' || exit 1; "))
        assertTrue(vol.contains("done < '/c/l.list' > '/c/l.list.live'"))
        assertTrue(vol.contains("'tar' '-c' '-f' '-' '--numeric-owner' '--no-recursion' '--selinux' '-C' '/' '-T' '/c/l.list.live' 2>&1 >&4 4>&-"))
        assertTrue(vol.contains("grep -v -F 'No such file or directory' | grep -q .; then __rc=0"))
        assertTrue(vol.endsWith("exit \"\$__rc\""))
        val facts = NandroidScripts.parseFacts(listOf("serial=ABC", "bootserial=", "display=UP1A.1", "datafs=ext4", "datafs=f2fs"))
        assertEquals("ABC", facts["serial"])
        assertEquals("f2fs", facts["datafs"])
        assertEquals("", facts["bootserial"])
    }
}
