package ca.devdcs.backup.core.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Covers the decision logic behind [NativeToolProvisioner.locate] via its testable seam
 * [NativeToolProvisioner.locateInDir]. The behaviour under test is the one that clears a
 * `StorageException.Reason.TOOL_MISSING`: once `lib<tool>.so` is staged in `nativeLibraryDir`
 * (as `scripts/fetch_prebuilts.sh` does) and the app uid can exec it, the tool resolves to
 * [ToolSource.NATIVE_LIB_DIR] with `available = true` and a path. Runs on the plain JVM — no
 * Android Context and no `dftb_native` JNI library required — because the seam takes the directory
 * and an executability predicate as parameters.
 */
class NativeToolProvisionerLocateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun stage(libName: String): File = tmp.newFile(libName).apply { writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())) }

    private fun locate(dir: String, toolId: String, exec: (String) -> Boolean) =
        NativeToolProvisioner.locateInDir(dir, toolId, exec)

    @Test
    fun `rclone busybox and zstd resolve once their staged lib is present and executable`() {
        // The exact file names fetch_prebuilts.sh stages — note zstd's _bin suffix.
        val cases = mapOf(
            "rclone" to "librclone.so",
            "busybox" to "libbusybox.so",
            "zstd" to "libzstd_bin.so",
        )
        for ((toolId, libName) in cases) {
            stage(libName)
            val result = locate(tmp.root.path, toolId) { true }
            assertTrue("$toolId must resolve when $libName is present", result.available)
            assertEquals(ToolSource.NATIVE_LIB_DIR, result.source)
            assertEquals("${tmp.root.path}/$libName", result.path)
            assertEquals(toolId, result.toolId)
        }
    }

    @Test
    fun `tool is absent (TOOL_MISSING stands) when its lib is not staged`() {
        // Directory is empty: nothing staged.
        val result = locate(tmp.root.path, "rclone") { true }
        assertFalse(result.available)
        assertEquals(ToolSource.ABSENT, result.source)
        assertNull(result.path)
    }

    @Test
    fun `staged but non-executable lib is reported absent, never as runnable`() {
        stage("libbusybox.so")
        val result = locate(tmp.root.path, "busybox") { false }
        assertFalse("a present-but-unexecutable copy must not be offered as runnable", result.available)
        assertEquals(ToolSource.ABSENT, result.source)
    }

    @Test
    fun `an unregistered tool id falls back to the lib id so convention`() {
        stage("libmystery.so")
        val result = locate(tmp.root.path, "mystery") { true }
        assertTrue(result.available)
        assertEquals("${tmp.root.path}/libmystery.so", result.path)
    }
}
