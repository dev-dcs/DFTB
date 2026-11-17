package ca.devdcs.backup.core.discovery.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FindOutputParserTest {

    private val parser = FindOutputParser("/sdcard/")

    @Test
    fun `parses directory and file lines`() {
        assertEquals(FindLine.Directory("DCIM", "/sdcard/DCIM"), parser.parseStdout("D /sdcard/DCIM"))
        assertEquals(
            FindLine.File("DCIM/My Photo 1.jpg", "/sdcard/DCIM/My Photo 1.jpg", 1234, 1_697_712_345_123),
            parser.parseStdout("F 1234 1697712345.1234567890 /sdcard/DCIM/My Photo 1.jpg"),
        )
    }

    @Test
    fun `accepts integer mtime from stat and doubled separators`() {
        assertEquals(
            FindLine.File("a.txt", "/sdcard//a.txt", 5, 1_000_000),
            parser.parseStdout("F 5 1000 /sdcard//a.txt"),
        )
    }

    @Test
    fun `root itself and blank lines are ignored`() {
        assertEquals(FindLine.Ignored, parser.parseStdout("D /sdcard/"))
        assertEquals(FindLine.Ignored, parser.parseStdout("D /sdcard"))
        assertEquals(FindLine.Ignored, parser.parseStdout(""))
    }

    @Test
    fun `malformed lines are unparseable`() {
        assertTrue(parser.parseStdout("second half of a name") is FindLine.Unparseable)
        assertTrue(parser.parseStdout("F abc 1 /sdcard/x") is FindLine.Unparseable)
        assertTrue(parser.parseStdout("F 1 1.x2 /sdcard/x") is FindLine.Unparseable)
        assertTrue(parser.parseStdout("F 1 2") is FindLine.Unparseable)
        assertTrue(parser.parseStdout("F 1 2 /elsewhere/x") is FindLine.Unparseable)
        // Prefix match must be segment-wise.
        assertTrue(parser.parseStdout("D /sdcardX/y") is FindLine.Unparseable)
    }

    @Test
    fun `filesystem root relativizes everything`() {
        val rootParser = FindOutputParser("/")
        assertEquals(FindLine.Directory("data/app", "/data/app"), rootParser.parseStdout("D /data/app"))
        assertEquals(FindLine.Ignored, rootParser.parseStdout("D /"))
    }

    @Test
    fun `single file mode uses the file name`() {
        val single = FindOutputParser("/data/local/tmp/x.bin", singleFile = true)
        assertEquals(
            FindLine.File("x.bin", "/data/local/tmp/x.bin", 10, 2000),
            single.parseStdout("F 10 2 /data/local/tmp/x.bin"),
        )
    }

    @Test
    fun `stderr in GNU and toybox formats`() {
        val gnu = parser.parseStderr("find: '/sdcard/Android/data': Permission denied") as FindLine.Error
        assertEquals("/sdcard/Android/data", gnu.path)
        assertEquals(RootProblem.NOT_READABLE, gnu.problem)
        assertFalse(gnu.isRoot)

        val toybox = parser.parseStderr("find: /sdcard/x: Permission denied") as FindLine.Error
        assertEquals("/sdcard/x", toybox.path)

        val root = parser.parseStderr("find: '/sdcard/': No such file or directory") as FindLine.Error
        assertTrue(root.isRoot)
        assertEquals(RootProblem.NOT_FOUND, root.problem)

        val notDir = parser.parseStderr("find: /sdcard/: Not a directory") as FindLine.Error
        assertEquals(RootProblem.NOT_A_DIRECTORY, notDir.problem)
        assertTrue(notDir.isRoot)

        val other = parser.parseStderr("find: bad option -printf") as FindLine.Error
        assertNull(other.path)
        assertEquals(RootProblem.TOOL_FAILED, other.problem)
        assertEquals(FindLine.Ignored, parser.parseStderr("  "))
    }

    @Test
    fun `epoch parsing`() {
        assertEquals(1_000L, FindOutputParser.parseEpochToMillis("1"))
        assertEquals(1_500L, FindOutputParser.parseEpochToMillis("1.5"))
        assertEquals(1_123L, FindOutputParser.parseEpochToMillis("1.123456789"))
        assertEquals(1_000L, FindOutputParser.parseEpochToMillis("1."))
        assertNull(FindOutputParser.parseEpochToMillis("x"))
    }
}
