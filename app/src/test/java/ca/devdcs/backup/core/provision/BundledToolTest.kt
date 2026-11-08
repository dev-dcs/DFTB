package ca.devdcs.backup.core.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledToolTest {

    @Test
    fun `ids are unique, lowercase and non-blank`() {
        val ids = BundledTool.entries.map { it.id }
        assertEquals("ids must be unique", ids.size, ids.toSet().size)
        for (id in ids) {
            assertTrue("'$id' must be non-blank", id.isNotBlank())
            assertEquals("'$id' must be lowercase", id.lowercase(), id)
        }
    }

    @Test
    fun `lib file name is lib id so except zstd`() {
        for (tool in BundledTool.entries) {
            val expected = if (tool == BundledTool.ZSTD) "libzstd_bin.so" else "lib${tool.id}.so"
            assertEquals(expected, tool.libFileName)
        }
        assertEquals("librclone.so", BundledTool.RCLONE.libFileName)
        assertEquals("libbusybox.so", BundledTool.BUSYBOX.libFileName)
        assertEquals("libdftbtool.so", BundledTool.DFTBTOOL.libFileName)
        // zstd is deliberately libzstd_bin.so, never libzstd.so (avoids any JNI .so name clash).
        assertEquals("libzstd_bin.so", BundledTool.ZSTD.libFileName)
    }

    @Test
    fun `fromId resolves known ids and rejects unknown`() {
        for (tool in BundledTool.entries) {
            assertSame(tool, BundledTool.fromId(tool.id))
        }
        assertNull(BundledTool.fromId("does-not-exist"))
        assertNull(BundledTool.fromId(""))
        assertNull(BundledTool.fromId("RCLONE"))
    }

    @Test
    fun `registry contains the expected tools including the built-in dftbtool`() {
        val ids = BundledTool.entries.map { it.id }.toSet()
        assertEquals(
            setOf("rclone", "busybox", "nsenter", "zstd", "xz", "pigz", "openssl", "tar", "dftbtool"),
            ids,
        )
    }

    @Test
    fun `every tool maps to a feature and carries a purpose resource`() {
        for (tool in BundledTool.entries) {
            // feature is a non-null enum by construction; purposeRes must be a real resource id.
            assertTrue("${tool.id} purposeRes must be set", tool.purposeRes != 0)
            assertTrue("${tool.feature} labelRes must be set", tool.feature.labelRes != 0)
        }
    }
}
