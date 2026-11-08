package ca.devdcs.backup.core.restore.apps

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class SafeDataTarTest {

    private sealed interface Member {
        data class Dir(val name: String) : Member
        data class File(val name: String, val body: String) : Member
        data class Link(val name: String, val target: String) : Member
        data class Hard(val name: String, val target: String) : Member
        data class Fifo(val name: String) : Member
    }

    private fun tarOf(vararg members: Member): ByteArray {
        val bytes = ByteArrayOutputStream()
        TarArchiveOutputStream(bytes).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for (m in members) {
                when (m) {
                    is Member.Dir -> tar.putArchiveEntry(TarArchiveEntry(m.name + "/", TarConstants.LF_DIR).apply { mode = 0x41F9 })
                    is Member.File -> {
                        val data = m.body.toByteArray()
                        tar.putArchiveEntry(TarArchiveEntry(m.name, TarConstants.LF_NORMAL).apply { size = data.size.toLong(); mode = 0x81B0 })
                        tar.write(data)
                    }
                    is Member.Link -> tar.putArchiveEntry(TarArchiveEntry(m.name, TarConstants.LF_SYMLINK).apply { linkName = m.target })
                    is Member.Hard -> tar.putArchiveEntry(TarArchiveEntry(m.name, TarConstants.LF_LINK).apply { linkName = m.target })
                    is Member.Fifo -> tar.putArchiveEntry(TarArchiveEntry(m.name, TarConstants.LF_FIFO))
                }
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun sanitize(input: ByteArray, includeCache: Boolean = false): Pair<List<String>, SafeTarStats> {
        val out = ByteArrayOutputStream()
        val writer = SafeDataTarWriter(out, "com.example", includeCache)
        SafeDataTarWriter.copyTar(ByteArrayInputStream(input), writer) { false }
        val stats = writer.finish()
        val names = ArrayList<String>()
        TarArchiveInputStream(ByteArrayInputStream(out.toByteArray())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                names += e.name + if (e.isSymbolicLink) " -> " + e.linkName else ""
            }
        }
        return names to stats
    }

    private fun assertRefused(vararg members: Member) {
        try {
            sanitize(tarOf(*members))
            fail("expected UnsafeDataTarException")
        } catch (expected: UnsafeDataTarException) {
            // refused
        }
    }

    @Test
    fun `passes app members, drops lib, cache and fifos`() {
        val (names, stats) = sanitize(
            tarOf(
                Member.Dir("com.example"),
                Member.Link("com.example/lib", "/data/app/~~x/com.example-1/lib/arm64"),
                Member.Dir("com.example/databases"),
                Member.File("com.example/databases/a.db", "db"),
                Member.Dir("com.example/cache"),
                Member.File("com.example/cache/c.bin", "cache"),
                Member.Fifo("com.example/pipe"),
                Member.Link("com.example/files/current", "/data/data/com.example/files/v2"),
                Member.Hard("com.example/databases/b.db", "com.example/databases/a.db"),
            ),
        )
        assertEquals(
            listOf(
                "com.example/",
                "com.example/databases/",
                "com.example/databases/a.db",
                "com.example/files/current -> /data/data/com.example/files/v2",
                "com.example/databases/b.db",
            ),
            names,
        )
        assertEquals(1, stats.droppedLib)
        assertEquals(2, stats.droppedCache)
        assertEquals(1, stats.droppedSpecial)
        assertEquals(2L, stats.bytes)
    }

    @Test
    fun `cache is kept when requested`() {
        val (names, _) = sanitize(tarOf(Member.File("com.example/cache/c.bin", "x"), Member.File("./com.example/code_cache/d", "y")), includeCache = true)
        assertEquals(listOf("com.example/cache/c.bin", "com.example/code_cache/d"), names)
    }

    @Test
    fun `refuses traversal, foreign roots and absolute names`() {
        assertRefused(Member.File("com.example/../../system/x", "x"))
        assertRefused(Member.File("com.other/x", "x"))
        assertRefused(Member.File("com.examplex/x", "x"))
        assertRefused(Member.File("/data/data/com.example/x", "x"))
    }

    @Test
    fun `refuses writing through a symlink created earlier`() {
        assertRefused(Member.Link("com.example/evil", "/data/system"), Member.File("com.example/evil/packages.xml", "x"))
        assertRefused(Member.Link("com.example/evil", "/data/system"), Member.Dir("com.example/evil/sub"))
        assertRefused(Member.Link("com.example", "/data/system"))
    }

    @Test
    fun `refuses hard links leaving the tree or pointing at nothing extracted`() {
        assertRefused(Member.Hard("com.example/h", "com.other/secret"))
        assertRefused(Member.Hard("com.example/h", "com.example/never"))
    }

    @Test
    fun `hard link to a dropped cache file is dropped too`() {
        val (names, _) = sanitize(tarOf(Member.File("com.example/cache/a", "x"), Member.Hard("com.example/files/a", "com.example/cache/a")))
        assertTrue(names.isEmpty())
    }

    @Test
    fun `normalize strips dot segments`() {
        assertEquals("com.example/a/b", SafeDataTarWriter.normalize("./com.example//./a/b/"))
    }
}
