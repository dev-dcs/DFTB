package ca.devdcs.backup.core.discovery.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindCommandBuilderTest {

    @Test
    fun `printf dialect recursive without pruning`() {
        val argv = FindCommandBuilder.build("/sdcard", recursive = true, dialect = FindDialect.PRINTF)
        assertEquals(
            listOf(
                "find", "/sdcard/", "-xdev", "-mindepth", "1",
                "(", "-type", "d", "-printf", "D %p\\n", ")", "-o",
                "(", "-type", "f", "-printf", "F %s %T@ %p\\n", ")",
            ),
            argv,
        )
    }

    @Test
    fun `non recursive adds maxdepth and exec stat dialect`() {
        val argv = FindCommandBuilder.build("/data/media/0/", recursive = false, dialect = FindDialect.EXEC_STAT)
        assertEquals(listOf("find", "/data/media/0/", "-xdev", "-mindepth", "1", "-maxdepth", "1"), argv.take(7))
        assertTrue(argv.containsAll(listOf("-exec", "stat", "-c", "F %s %Y %n", "{}", "+")))
        assertFalse("-printf" in argv)
    }

    @Test
    fun `prune clause precedes the print branches`() {
        val argv = FindCommandBuilder.build(
            "/sdcard", true, FindDialect.PRINTF,
            pruneDirNames = listOf("cache", ".trash*"), prunePaths = listOf("*/Android/data"),
        )
        val prune = listOf(
            "(", "-type", "d", "(", "-name", "cache", "-o", "-name", ".trash*", "-o", "-path", "*/Android/data", ")", "-prune", ")", "-o",
        )
        assertEquals(prune, argv.subList(5, 5 + prune.size))
    }

    @Test
    fun `single file mode keeps the path exact`() {
        val argv = FindCommandBuilder.build("/x/y.bin/", true, FindDialect.PRINTF, singleFile = true, pruneDirNames = listOf("cache"))
        assertEquals(listOf("find", "/x/y.bin", "-xdev", "-maxdepth", "0"), argv.take(5))
        assertFalse("-prune" in argv)
    }

    @Test
    fun `root normalization`() {
        assertEquals("/", FindCommandBuilder.findRoot("/", singleFile = false))
        assertEquals("/sdcard/", FindCommandBuilder.findRoot("/sdcard//", singleFile = false))
        assertEquals("/", FindCommandBuilder.findRoot("/", singleFile = true))
    }

    @Test
    fun `default prune names cover the exclusion matcher`() {
        val names = FindCommandBuilder.defaultPruneNames()
        assertTrue(names.containsAll(ExclusionMatcher.EXCLUDED_DIR_NAMES))
        assertTrue(".trash*" in names)
        assertEquals(listOf("find", "/", "-maxdepth", "0", "-printf", "${FindCommandBuilder.PROBE_MARKER} %s %T@\\n"), FindCommandBuilder.probeCommand())
    }

    @Test
    fun `probe output must expand the real directives`() {
        val m = FindCommandBuilder.PROBE_MARKER
        assertTrue(FindCommandBuilder.isProbeOutputValid("$m 4096 1700000000.123"))
        assertTrue(FindCommandBuilder.isProbeOutputValid("$m 4096 1700000000"))
        assertFalse(FindCommandBuilder.isProbeOutputValid(m))
        assertFalse(FindCommandBuilder.isProbeOutputValid("$m %s %T@"))
        assertFalse(FindCommandBuilder.isProbeOutputValid("$m 4096 T@"))
    }

    @Test
    fun `android data prune is anchored below the root`() {
        assertEquals(listOf("/sdcard/Android/data", "/sdcard/*/Android/data"), FindCommandBuilder.defaultPrunePaths("/sdcard"))
        // A root of .../Android keeps its data child: neither glob matches /sdcard/Android/data.
        val inAndroid = FindCommandBuilder.defaultPrunePaths("/sdcard/Android/")
        assertEquals(listOf("/sdcard/Android/Android/data", "/sdcard/Android/*/Android/data"), inAndroid)
        assertEquals(listOf("/Android/data", "/*/Android/data"), FindCommandBuilder.defaultPrunePaths("/"))
        assertEquals("/x/a\\*b\\[1\\]\\?/", FindCommandBuilder.escapeGlob("/x/a*b[1]?/"))
    }
}
