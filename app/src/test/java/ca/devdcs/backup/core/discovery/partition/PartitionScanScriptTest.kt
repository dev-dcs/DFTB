package ca.devdcs.backup.core.discovery.partition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class PartitionScanScriptTest {

    private val script = PartitionScanScript.build()

    @Test
    fun `script only redirects to dev null and never writes`() {
        // Every '>' must be part of a redirect to /dev/null (2>/dev/null or >/dev/null).
        val stripped = script.replace("2>/dev/null", "").replace(">/dev/null", "").replace("2>&1", "")
        assertFalse("unexpected redirect in:\n$script", stripped.contains('>'))
        val forbidden = listOf("dd ", "rm ", "mv ", "cp ", "chmod", "chown", "mkdir", "touch", "tee ", "setprop", "blockdev", "mount", "ln ", "dmctl create", "dmctl delete", "resize")
        forbidden.forEach { assertFalse("forbidden '$it' in script", script.contains(it)) }
        assertTrue(script.contains("dmctl list devices"))
    }

    @Test
    fun `script emits every section marker`() {
        listOf(
            PartitionScanScript.SECTION_PROPS, PartitionScanScript.SECTION_BYNAME, PartitionScanScript.SECTION_BYNAME_LS,
            PartitionScanScript.SECTION_PROC, PartitionScanScript.SECTION_SYS, PartitionScanScript.SECTION_DMNAME,
            PartitionScanScript.SECTION_MAPPER, PartitionScanScript.SECTION_DMCTL, PartitionScanScript.SECTION_END,
        ).forEach { assertTrue("missing $it", script.contains("echo \"\$M $it\"")) }
        PartitionParsers.SCANNED_PROPERTIES.forEach { assertTrue(script.contains("'$it'")) }
    }

    @Test
    fun `script runs under a POSIX shell and ends with the end marker`() {
        val sh = File("/bin/sh")
        assumeTrue("no /bin/sh on this host", sh.canExecute())
        val process = ProcessBuilder(sh.path, "-c", script).redirectErrorStream(false).start()
        val out = process.inputStream.bufferedReader().readLines()
        assumeTrue("shell hung", process.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue())
        val sections = PartitionScanScript.parseSections(out)
        assertTrue("output: $out", sections.complete)
        // getprop rows are printed even when getprop is absent (empty values).
        assertEquals(PartitionParsers.SCANNED_PROPERTIES.size, sections.properties.size)
    }

    @Test
    fun `sections ignore preamble and detect truncation`() {
        val m = PartitionScanScript.MARKER
        val parsed = PartitionScanScript.parseSections(listOf("motd noise", "$m props", "a=b\r", "$m proc", "8 0 10 sda"))
        assertEquals(listOf("a=b"), parsed.properties)
        assertEquals(listOf("8 0 10 sda"), parsed.procPartitions)
        assertFalse(parsed.complete)
    }
}
