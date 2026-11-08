package ca.devdcs.backup.core.discovery.files

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class NioFileWalkerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(rel: String, bytes: Int): File =
        File(tmp.root, rel).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }

    @Test
    fun `walks files prunes exclusions and never follows symlinks`() = runTest {
        write("DCIM/a.jpg", 3)
        write("DCIM/.thumbnails/t.jpg", 1)
        write("Docs/b.txt", 5)
        Files.createSymbolicLink(File(tmp.root, "DCIM/loop").toPath(), tmp.root.toPath())
        Files.createSymbolicLink(File(tmp.root, "link.jpg").toPath(), File(tmp.root, "DCIM/a.jpg").toPath())

        val walker = NioFileWalker(UnconfinedTestDispatcher(testScheduler))
        val events = walker.walk(WalkSpec(tmp.root.path, recursive = true, exclusions = ExclusionMatcher.DEFAULT)).toList()
        val files = events.filterIsInstance<WalkEvent.File>().associate { it.relativePath to it.sizeBytes }
        assertEquals(mapOf("DCIM/a.jpg" to 3L, "Docs/b.txt" to 5L), files)
        val dirs = events.filterIsInstance<WalkEvent.Directory>().map { it.relativePath }.toSet()
        assertEquals(setOf("", "DCIM", "Docs"), dirs)
    }

    @Test
    fun `hidden dirs are reported unreadable and not listed`() = runTest {
        write("Android/obb/com.game/main.obb", 7)
        write("Android/media/m.mp3", 1)
        val walker = NioFileWalker(UnconfinedTestDispatcher(testScheduler))
        val events = walker.walk(WalkSpec(tmp.root.path, true, ExclusionMatcher.DEFAULT, hiddenDirs = setOf("Android/obb"))).toList()
        assertEquals(listOf("Android/media/m.mp3"), events.filterIsInstance<WalkEvent.File>().map { it.relativePath })
        assertEquals(listOf("Android/obb"), events.filterIsInstance<WalkEvent.Unreadable>().map { it.path })
    }

    @Test
    fun `non recursive single file and missing root`() = runTest {
        write("top.bin", 2)
        write("sub/deep.bin", 4)
        val walker = NioFileWalker(UnconfinedTestDispatcher(testScheduler))
        val shallow = walker.walk(WalkSpec(tmp.root.path, recursive = false, exclusions = ExclusionMatcher.NONE)).toList()
        assertEquals(listOf("top.bin"), shallow.filterIsInstance<WalkEvent.File>().map { it.relativePath })

        val single = walker.walk(WalkSpec(File(tmp.root, "sub/deep.bin").path, true, ExclusionMatcher.NONE)).toList()
        assertEquals(listOf(WalkEvent.File("deep.bin", File(tmp.root, "sub/deep.bin").path, 4, File(tmp.root, "sub/deep.bin").lastModified())), single)

        val missing = walker.walk(WalkSpec(File(tmp.root, "nope").path, true, ExclusionMatcher.NONE)).toList()
        assertTrue(missing.single() is WalkEvent.RootUnavailable)
        assertEquals(RootProblem.NOT_FOUND, (missing.single() as WalkEvent.RootUnavailable).problem)
    }
}
