package ca.devdcs.backup.core.storage.nfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfsMountPlannerTest {

    @Test
    fun `base path is the export unless export option is set`() {
        assertEquals(NfsMountPlanner.Target("/srv/backups", ""), NfsMountPlanner.resolveTarget("/srv/backups/", null))
        assertEquals(NfsMountPlanner.Target("/srv", "phone/daily"), NfsMountPlanner.resolveTarget("phone/daily", "/srv"))
        assertNull(NfsMountPlanner.resolveTarget("relative", null))
        assertNull(NfsMountPlanner.resolveTarget(null, null))
        assertNull(NfsMountPlanner.resolveTarget("/x", "noslash"))
    }

    @Test
    fun `mountpoint sanitises the destination id`() {
        assertEquals("/data/local/tmp/dftb_mnt/abc-123_x", NfsMountPlanner.mountPoint("abc-123_x"))
        assertEquals("/data/local/tmp/dftb_mnt/___etc", NfsMountPlanner.mountPoint("../etc"))
        assertEquals("/data/local/tmp/dftb_mnt/default", NfsMountPlanner.mountPoint(""))
    }

    @Test
    fun `defaults merge with overrides, enforced hardening and addr`() {
        assertEquals(
            "vers=3,nolock,proto=tcp,soft,timeo=600,nosuid,nodev,noexec,nosymfollow,addr=192.168.1.5",
            NfsMountPlanner.mergeOptions(NfsMountPlanner.DEFAULT_OPTIONS, null, "192.168.1.5", noSymFollow = true),
        )
        assertEquals(
            "vers=4.1,nolock,proto=tcp,timeo=100,hard,addr=10.0.0.9,nosuid,nodev,noexec",
            NfsMountPlanner.mergeOptions(NfsMountPlanner.DEFAULT_OPTIONS, "vers=4.1, hard,timeo=100,addr=10.0.0.9", "192.168.1.5", noSymFollow = false),
        )
        assertEquals(
            "vers=3,proto=tcp,soft,timeo=600,lock,nosuid,nodev,noexec,addr=1.2.3.4",
            NfsMountPlanner.mergeOptions(NfsMountPlanner.DEFAULT_OPTIONS, "lock", "1.2.3.4", noSymFollow = false),
        )
        // Even if an override slipped through, the enforced flags win.
        assertEquals(
            "vers=3,nolock,proto=tcp,soft,timeo=600,nosuid,nodev,noexec,addr=1.2.3.4",
            NfsMountPlanner.mergeOptions(NfsMountPlanner.DEFAULT_OPTIONS, "suid,exec", "1.2.3.4", noSymFollow = false),
        )
    }

    @Test
    fun `security-weakening overrides are detected`() {
        assertTrue(NfsMountPlanner.weakensSecurity("vers=4,suid"))
        assertTrue(NfsMountPlanner.weakensSecurity("exec"))
        assertTrue(NfsMountPlanner.weakensSecurity("dev, rsize=4096"))
        assertTrue(NfsMountPlanner.weakensSecurity("symfollow"))
        assertFalse(NfsMountPlanner.weakensSecurity("vers=4.2,hard,noexec"))
    }

    @Test
    fun `option safety check rejects shell metacharacters`() {
        assertTrue(NfsMountPlanner.optionsAreSafe("vers=3,nolock,addr=fe80::1,rsize=1048576"))
        assertFalse(NfsMountPlanner.optionsAreSafe("vers=3;reboot"))
        assertFalse(NfsMountPlanner.optionsAreSafe("vers=3 \$(id)"))
        assertFalse(NfsMountPlanner.optionsAreSafe("a'b"))
    }

    @Test
    fun `source brackets ipv6`() {
        assertEquals("192.168.1.5:/srv", NfsMountPlanner.source("192.168.1.5", "/srv"))
        assertEquals("[fd00::5]:/srv", NfsMountPlanner.source("fd00::5", "/srv"))
    }

    @Test
    fun `kernel support detection`() {
        val fs = NfsMountPlanner.parseFilesystems(listOf("nodev\tsysfs", "\text4", "nodev\tnfs", "nodev\tnfs4", ""))
        assertEquals(setOf("sysfs", "ext4", "nfs", "nfs4"), fs)
        assertEquals("nfs", NfsMountPlanner.chooseFsType(fs, "vers=3,nolock"))
        assertEquals("nfs4", NfsMountPlanner.chooseFsType(fs, "vers=4.2"))
        assertEquals("nfs", NfsMountPlanner.chooseFsType(setOf("nfs"), "vers=4"))
        assertNull(NfsMountPlanner.chooseFsType(setOf("ext4", "f2fs"), "vers=3"))
    }

    @Test
    fun `mount table parsing`() {
        val mounts = listOf(
            "/dev/block/dm-5 /data f2fs rw 0 0",
            "10.0.0.2:/srv /data/local/tmp/dftb_mnt/d1 nfs rw,vers=3 0 0",
        )
        assertTrue(NfsMountPlanner.isMounted(mounts, "/data/local/tmp/dftb_mnt/d1"))
        assertFalse(NfsMountPlanner.isMounted(mounts, "/data/local/tmp/dftb_mnt/d2"))
    }

    @Test
    fun `mount script quotes every argument`() {
        assertEquals(
            "mkdir -p '/data/local/tmp/dftb_mnt/d1' && mount -t 'nfs' -o 'vers=3,addr=1.2.3.4' '1.2.3.4:/srv' '/data/local/tmp/dftb_mnt/d1'",
            NfsMountPlanner.mountScript("nfs", "vers=3,addr=1.2.3.4", "1.2.3.4:/srv", "/data/local/tmp/dftb_mnt/d1"),
        )
        assertTrue(NfsMountPlanner.unmountScript("/m").startsWith("umount -l '/m'"))
    }
}
