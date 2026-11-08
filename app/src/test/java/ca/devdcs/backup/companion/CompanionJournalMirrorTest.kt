package ca.devdcs.backup.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Pins the journal sync script [CompanionJournalMirror] runs as root. It is executed here by the
 * JVM host's real POSIX `/bin/sh` against a temp directory, because what matters is the on-disk
 * result service.sh will see: the right files, with the exact markers it greps for, and no stale ones.
 */
class CompanionJournalMirrorTest {

    @Test
    fun `job ids are reduced to filename-safe stems`() {
        assertEquals("a1b2-c3_d4.e5", CompanionJournalMirror.safeFileStem("a1b2-c3_d4.e5"))
        assertEquals("_.._etc_passwd", CompanionJournalMirror.safeFileStem("/../etc/passwd"))
        assertEquals("x_y_z_", CompanionJournalMirror.safeFileStem("x y|z*"))
        assertEquals("job", CompanionJournalMirror.safeFileStem("..."))
    }

    @Test
    fun `sync writes entries with grep-able markers and removes stale files`() {
        val dir = Files.createTempDirectory("dftb-journal").toFile()
        File(dir, "stale.json").writeText("{}")
        File(dir, "keep-me.txt").writeText("not a journal file")
        val json = """{"jobId":"job-1","type":"backup","status":"running","resumeAfterReboot":true,"attempt":1}"""

        run(CompanionJournalMirror.buildSyncScript(dir.absolutePath, listOf("job-1" to json)))

        assertEquals(setOf("job-1.json", "keep-me.txt"), dir.list()!!.toSet())
        val written = File(dir, "job-1.json").readText().trim()
        assertEquals(json, written)
        // The exact patterns service.sh uses.
        assertTrue(Regex("\"resumeAfterReboot\":[ ]*true").containsMatchIn(written))
        assertTrue(Regex("\"status\":[ ]*\"(running|interrupted)\"").containsMatchIn(written))
    }

    @Test
    fun `json with single quotes and shell metacharacters is written verbatim`() {
        val dir = Files.createTempDirectory("dftb-journal").toFile()
        val json = """{"jobId":"j","profileId":"it's ${'$'}(rm -rf /) `x`","status":"interrupted","resumeAfterReboot":true}"""

        run(CompanionJournalMirror.buildSyncScript(dir.absolutePath, listOf("j" to json)))

        assertEquals(json, File(dir, "j.json").readText().trim())
    }

    @Test
    fun `empty entry list clears every journal file`() {
        val dir = Files.createTempDirectory("dftb-journal").toFile()
        File(dir, "a.json").writeText("{}")
        File(dir, "b.json").writeText("{}")

        run(CompanionJournalMirror.buildSyncScript(dir.absolutePath, emptyList()))

        assertEquals(emptySet<String>(), dir.list()!!.toSet())
    }

    private fun run(script: String) {
        val process = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("script output: $output", 0, process.exitValue())
    }
}
