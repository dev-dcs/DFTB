package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.domain.model.FileFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanExecutionTest {

    private class ListWalker(private val events: List<WalkEvent>, override val kind: WalkerKind = WalkerKind.NIO) : FileWalker {
        override fun walk(spec: WalkSpec): Flow<WalkEvent> = flowOf(*events.toTypedArray())
    }

    private fun root(location: String, walker: FileWalker?, filter: FileFilter = FileFilter.NONE, exclusions: ExclusionMatcher = ExclusionMatcher.DEFAULT) =
        ResolvedRoot(
            PlannedRoot(location, true, filter),
            walker,
            walker?.let { WalkSpec(location, true, exclusions) },
            RootOutcome(location, walker?.kind, blocked = if (walker == null) BlockReason.NEEDS_ROOT else null),
        )

    private fun f(rel: String, size: Long = 10, base: String = "/r") = WalkEvent.File(rel, "$base/$rel", size, 1000)

    @Test
    fun `filters exclusions and blocked roots`() = runTest {
        val walker = ListWalker(
            listOf(
                WalkEvent.Directory(""),
                WalkEvent.Directory("DCIM"),
                WalkEvent.Directory("DCIM/.thumbnails"),
                f("DCIM/a.jpg"),
                f("DCIM/.thumbnails/t.jpg"),
                f("DCIM/b.txt"),
                WalkEvent.Unreadable("DCIM/secret", "Permission denied"),
                WalkEvent.Warning(ScanWarning.UNPARSEABLE_OUTPUT, "1"),
            ),
        )
        val roots = listOf(
            root("/r", walker, FileFilter(includeExtensions = setOf("jpg"))),
            root("/data/data/x", null),
        )
        val done = ScanExecution.execute(roots, ScanOptions(collectFiles = true)).last() as ScanEvent.Completed
        val r = done.result
        assertEquals(listOf("DCIM/a.jpg"), r.files.map { it.relativePath })
        assertEquals(2, r.summary.dirsVisited)
        assertEquals(1, r.summary.skippedUnreadable)
        assertEquals(setOf(ScanWarning.UNPARSEABLE_OUTPUT), r.roots[0].warnings)
        assertEquals(BlockReason.NEEDS_ROOT, r.roots[1].blocked)
        assertEquals(listOf("/data/data/x"), r.blockedRoots.map { it.location })
        assertFalse(r.truncated)
    }

    @Test
    fun `overlapping roots are de-duplicated`() = runTest {
        val outer = ListWalker(listOf(f("DCIM/a.jpg", base = "/s"), f("x.jpg", base = "/s")))
        val inner = ListWalker(listOf(f("a.jpg", base = "/s/DCIM")))
        val result = (ScanExecution.execute(listOf(root("/s", outer), root("/s/DCIM", inner)), ScanOptions(collectFiles = true)).last() as ScanEvent.Completed).result
        assertEquals(2, result.summary.matchedCount)
        assertTrue(ScanExecution.rootsOverlap(listOf("/s", "/s/DCIM")))
        assertFalse(ScanExecution.rootsOverlap(listOf("/s/a", "/s/ab")))
        assertTrue(ScanExecution.rootsOverlap(listOf("content://x/tree/1", "content://x/tree/1")))
        assertTrue(ScanExecution.rootsOverlap(listOf("/sdcard", "/storage/emulated/0/DCIM")))
        assertTrue(ScanExecution.rootsOverlap(listOf("/storage/self/primary/", "/storage/emulated/0")))
        assertFalse(ScanExecution.rootsOverlap(listOf("/storage/emulated/10", "/sdcard/x")))
    }

    @Test
    fun `aliases de-duplicate while differing filters still union`() = runTest {
        // Outer /sdcard (all files) walked first; nested canonical category root DCIM (jpg only).
        val outer = ListWalker(listOf(f("DCIM/a.jpg", base = "/sdcard"), f("DCIM/b.pdf", base = "/sdcard"), f("x.jpg", base = "/sdcard")))
        val inner = ListWalker(listOf(f("a.jpg", base = "/storage/emulated/0/DCIM")))
        val roots = listOf(
            root("/sdcard", outer),
            root("/storage/emulated/0/DCIM", inner, FileFilter(includeExtensions = setOf("jpg"))),
        )
        val result = (ScanExecution.execute(roots, ScanOptions(collectFiles = true), primaryStorage = "/storage/emulated/0").last() as ScanEvent.Completed).result
        assertEquals(listOf("DCIM/b.pdf", "x.jpg", "a.jpg"), result.files.map { it.relativePath })
        assertEquals(3, result.summary.matchedCount)
    }

    @Test
    fun `identical roots are owned by the first and blocked nested roots cover nothing`() = runTest {
        val a = ListWalker(listOf(f("p.jpg"), f("q.txt")))
        val b = ListWalker(listOf(f("p.jpg"), f("q.txt")))
        val roots = listOf(root("/r", a, FileFilter(includeExtensions = setOf("jpg"))), root("/r", b))
        val result = (ScanExecution.execute(roots, ScanOptions(collectFiles = true)).last() as ScanEvent.Completed).result
        assertEquals(listOf("p.jpg", "q.txt"), result.files.map { it.relativePath })

        val outer = ListWalker(listOf(f("sub/z.bin")))
        val blocked = listOf(root("/r", outer), root("/r/sub", null))
        val r2 = (ScanExecution.execute(blocked, ScanOptions()).last() as ScanEvent.Completed).result
        assertEquals(1, r2.summary.matchedCount)
    }

    @Test
    fun `root problems are recorded but missing category folders are benign`() = runTest {
        val missing = ListWalker(listOf(WalkEvent.RootUnavailable(RootProblem.NOT_FOUND, "/s/Movies")))
        val plain = root("/s/Movies", missing)
        val category = plain.copy(planned = plain.planned.copy(categories = setOf(ca.devdcs.backup.domain.model.FileCategory.VIDEOS)))
        val r1 = (ScanExecution.execute(listOf(plain), ScanOptions()).last() as ScanEvent.Completed).result
        assertEquals(RootProblem.NOT_FOUND, r1.roots.single().rootProblem)
        val r2 = (ScanExecution.execute(listOf(category), ScanOptions()).last() as ScanEvent.Completed).result
        assertEquals(null, r2.roots.single().rootProblem)
    }

    @Test
    fun `progress is throttled by the clock`() = runTest {
        var now = 0L
        val walker = ListWalker((1..5).map { f("f$it.bin") })
        val events = ScanExecution.execute(listOf(root("/r", walker)), ScanOptions(progressIntervalMillis = 1), nanoClock = { now.also { now += 600_000 } }).toList()
        val progress = events.filterIsInstance<ScanEvent.Progress>()
        // Clock advances 0.6 ms per read: roughly every second file crosses 1 ms, plus the final one.
        assertTrue(progress.size in 2..4)
        assertEquals(5, progress.last().progress.filesMatched)
        assertTrue(events.last() is ScanEvent.Completed)
    }

    @Test
    fun `collector cancellation stops an endless walk`() = runTest {
        var produced = 0
        val endless = object : FileWalker {
            override val kind = WalkerKind.NIO
            override fun walk(spec: WalkSpec): Flow<WalkEvent> = flow {
                while (true) {
                    produced++
                    emit(WalkEvent.File("f$produced", "/r/f$produced", 1, 1))
                }
            }
        }
        val first = ScanExecution.execute(listOf(root("/r", endless)), ScanOptions(progressIntervalMillis = 0))
            .filterIsInstance<ScanEvent.Progress>()
            .first { it.progress.filesMatched >= 100 }
        assertEquals(100, first.progress.filesMatched)
        assertTrue(produced < 200)
    }
}
