package ca.devdcs.backup.core.storage.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class KnownHostsFormatTest {

    @Test
    fun `host ids are lowercase and bracket ipv6`() {
        assertEquals("nas.local:22", KnownHostsFormat.hostId("NAS.local", 22))
        assertEquals("[fe80::1]:2222", KnownHostsFormat.hostId("fe80::1", 2222))
        assertEquals("[fe80::1]:22", KnownHostsFormat.hostId("[FE80::1]", 22))
    }

    @Test
    fun `format and parse round trip ignoring comments`() {
        val entries = listOf(
            KnownHostEntry("nas:22", "ssh-ed25519", "SHA256:abc"),
            KnownHostEntry("[::1]:22", "ssh-rsa", "SHA256:def"),
        )
        val text = KnownHostsFormat.format(entries) + "\n# trailing comment\n   \n"
        assertEquals(entries, KnownHostsFormat.parse(text))
    }

    @Test(expected = java.io.IOException::class)
    fun `corrupt line fails closed instead of forgetting pins`() {
        KnownHostsFormat.parse("nas:22 ssh-ed25519 SHA256:abc\nbroken line\n")
    }

    @Test
    fun `fingerprint matches openssh style sha256 without padding`() {
        val blob = byteArrayOf(0, 0, 0, 11) + "ssh-ed25519".toByteArray()
        val expected = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        assertEquals(expected, KnownHostsFormat.fingerprint(blob))
        assertTrue(!KnownHostsFormat.fingerprint(blob).endsWith("="))
    }

    @Test
    fun `verdicts never accept a changed key`() {
        val stored = listOf(KnownHostEntry("h:22", "ssh-ed25519", "SHA256:good"))
        assertEquals(KnownHostsFormat.Verdict.MATCH, KnownHostsFormat.verdict(stored, "h:22", "ssh-ed25519", "SHA256:good"))
        assertEquals(KnownHostsFormat.Verdict.MISMATCH, KnownHostsFormat.verdict(stored, "h:22", "ssh-ed25519", "SHA256:evil"))
        assertEquals(KnownHostsFormat.Verdict.MISMATCH, KnownHostsFormat.verdict(stored, "h:22", "ecdsa-sha2-nistp256", "SHA256:good"))
        assertEquals(KnownHostsFormat.Verdict.UNKNOWN_HOST, KnownHostsFormat.verdict(stored, "h:2222", "ssh-ed25519", "SHA256:good"))
    }

    @Test
    fun `rsa keys prefer sha2 signature algorithms`() {
        assertEquals(listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa"), KnownHostsFormat.algorithmsFor("ssh-rsa"))
        assertEquals(listOf("ssh-ed25519"), KnownHostsFormat.algorithmsFor("ssh-ed25519"))
    }
}
