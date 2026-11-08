package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.domain.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAggregatorTest {

    private fun file(rel: String, size: Long, mtime: Long) =
        ScannedFile("/r", rel, "/r/$rel", size, mtime, WalkerKind.NIO)

    @Test
    fun `top N keeps the greatest elements`() {
        val top = TopN(3, naturalOrder<Int>())
        listOf(5, 1, 9, 3, 7, 2).forEach(top::offer)
        assertEquals(listOf(9, 7, 5), top.snapshot())
        val none = TopN(0, naturalOrder<Int>())
        none.offer(1)
        assertTrue(none.snapshot().isEmpty())
    }

    @Test
    fun `aggregates extensions categories and extremes`() {
        val agg = ScanAggregator(topN = 2)
        agg.onDirectory()
        agg.onDirectory()
        agg.onUnreadable()
        agg.onMatch(file("a.jpg", 100, 3000))
        agg.onMatch(file("b.JPG", 50, 1000))
        agg.onMatch(file("c.mp4", 1000, 5000))
        agg.onMatch(file("README", 1, 0))
        agg.onMatch(file("d.pdf", 10, 2000))
        val s = agg.summary()
        assertEquals(5, s.matchedCount)
        assertEquals(1161, s.matchedBytes)
        assertEquals(2, s.dirsVisited)
        assertEquals(1, s.skippedUnreadable)
        assertEquals(listOf(ExtensionStat("jpg", 2, 150), ExtensionStat("mp4", 1, 1000)), s.byExtension)
        assertEquals(CategoryStat(2, 150), s.byCategory[FileCategory.IMAGES])
        assertEquals(CategoryStat(1, 1000), s.byCategory[FileCategory.VIDEOS])
        assertEquals(CategoryStat(1, 10), s.byCategory[FileCategory.DOCUMENTS])
        assertEquals(CategoryStat(1, 1), s.unclassified)
        assertEquals(listOf("c.mp4", "a.jpg"), s.largestFiles.map { it.relativePath })
        // mtime 0 (unknown) never wins oldest.
        assertEquals("b.JPG", s.oldest?.relativePath)
        assertEquals("c.mp4", s.newest?.relativePath)
        assertTrue(agg.files().isEmpty())
    }

    @Test
    fun `collection is capped and flagged`() {
        val agg = ScanAggregator(collectFiles = true, maxCollected = 2)
        repeat(3) { agg.onMatch(file("f$it.txt", 1, 1)) }
        assertEquals(2, agg.files().size)
        assertTrue(agg.truncated)
        assertEquals(3, agg.summary().matchedCount)

        val ok = ScanAggregator(collectFiles = true, maxCollected = 5)
        ok.onMatch(file("x", 1, 1))
        assertFalse(ok.truncated)
        assertEquals(ScanProgress(0, 1, 1, "p"), ok.progress("p"))
    }
}
