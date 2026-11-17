package ca.devdcs.backup.core.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure, Context-free helpers in [NativeToolProvisioner.Companion] that guard the shell
 * paths: tool-id sanitisation and single-quoting. The provisioner's filesystem/shell methods need a
 * device and are exercised by instrumented tests instead.
 */
class ProvisionShellHelpersTest {

    @Test
    fun `sanitizeToolId accepts plain tool names`() {
        for (id in listOf("rclone", "busybox", "dftbtool", "zstd", "xz", "pigz", "openssl", "tar", "a.b-c_1")) {
            assertEquals(id, NativeToolProvisioner.sanitizeToolId(id))
        }
    }

    @Test
    fun `sanitizeToolId rejects shell-unsafe or empty ids`() {
        for (bad in listOf("", " ", "rm -rf", "a;b", "a/b", "a\$b", "a`b`", "a\nb", "../x", "a|b", "\"q\"")) {
            assertNull("'$bad' should be rejected", NativeToolProvisioner.sanitizeToolId(bad))
        }
    }

    @Test
    fun `sanitizeToolId rejects over-long ids`() {
        assertNull(NativeToolProvisioner.sanitizeToolId("a".repeat(65)))
        assertEquals("a".repeat(64), NativeToolProvisioner.sanitizeToolId("a".repeat(64)))
    }

    @Test
    fun `singleQuote wraps a plain string`() {
        assertEquals("'/data/local/tmp/dftb_bin'", NativeToolProvisioner.singleQuote("/data/local/tmp/dftb_bin"))
    }

    @Test
    fun `singleQuote escapes embedded single quotes`() {
        // a'b -> 'a'\''b'
        assertEquals("'a'\\''b'", NativeToolProvisioner.singleQuote("a'b"))
    }

    @Test
    fun `singleQuote neutralises shell metacharacters by quoting`() {
        val quoted = NativeToolProvisioner.singleQuote("x; rm -rf / #")
        assertEquals("'x; rm -rf / #'", quoted)
    }
}
