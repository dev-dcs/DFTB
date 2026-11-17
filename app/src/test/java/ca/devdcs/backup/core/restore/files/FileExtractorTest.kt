package ca.devdcs.backup.core.restore.files

import ca.devdcs.backup.core.pipeline.EntryMetadata
import ca.devdcs.backup.core.pipeline.archive.ArchiveEntryInfo
import ca.devdcs.backup.core.pipeline.archive.ArchiveEntryType
import ca.devdcs.backup.core.pipeline.checksum.DefaultChecksumEngine
import ca.devdcs.backup.core.restore.ConflictPolicy
import ca.devdcs.backup.core.restore.catalog.RestoreFixtures
import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class FileExtractorTest {

    private val io = Dispatchers.Unconfined
    private val sandbox: File = Files.createTempDirectory("dftb-extract").toFile()
    private val root = File(sandbox, "root").apply { mkdirs() }
    private val outside = File(sandbox, "outside").apply { mkdirs() }
    private val target = NioTargetFileSystem(root, io)

    private val progress = object : ExtractProgress {
        var bytes = 0L
        override fun onBytes(delta: Long) {
            bytes += delta
        }
        override fun onItem(path: String) = Unit
        override fun isCancelled(): Boolean = false
    }

    private fun extractor(policy: ConflictPolicy = ConflictPolicy.OVERWRITE, route: (String) -> RoutedPath? = { RoutedPath(target, it) }) =
        FileExtractor(route, policy, restoreMetadata = true, checksumEngine = DefaultChecksumEngine(io), io = io, progress = progress)

    private fun fileEntry(path: String, content: String, digest: String? = RestoreFixtures.sha256(content.toByteArray()), mtime: Long? = null) = ExtractEntry(
        ArchiveEntryInfo(path, ArchiveEntryType.FILE, content.length.toLong(), null, EntryMetadata(modifiedAtMillis = mtime)),
        { ByteArrayInputStream(content.toByteArray()) },
        digest?.let { mapOf(ChecksumAlgorithm.SHA256 to it) }.orEmpty(),
    )

    private fun linkEntry(path: String, target: String) =
        ExtractEntry(ArchiveEntryInfo(path, ArchiveEntryType.SYMLINK, 0, target, EntryMetadata()), null)

    private fun dirEntry(path: String) = ExtractEntry(ArchiveEntryInfo(path, ArchiveEntryType.DIRECTORY, 0, null, EntryMetadata()), null)

    private fun hardLinkEntry(path: String, target: String) =
        ExtractEntry(ArchiveEntryInfo(path, ArchiveEntryType.HARD_LINK, 0, target, EntryMetadata()), null)

    @Test
    fun writesFilesCreatesParentsAndRestoresMtime() = runTest {
        val x = extractor()
        x.extract(fileEntry("a/b/c.txt", "hello", mtime = 1_600_000_000_000L))
        val summary = x.finish()
        val written = File(root, "a/b/c.txt")
        assertEquals("hello", written.readText())
        assertEquals(1_600_000_000_000L, written.lastModified())
        assertEquals(1, summary.files)
        assertEquals(2, summary.directories)
        assertEquals(5L, summary.bytes)
        assertEquals(0, summary.failed)
        // No temp files left behind.
        assertTrue(File(root, "a/b").listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun traversalAndAbsolutePathsAreRefused() = runTest {
        val x = extractor()
        x.extract(fileEntry("ok.txt", "fine"))
        // A router (or a hostile mapping) producing escaping paths must be caught by the extractor.
        val escaping = extractor(route = { RoutedPath(target, it) })
        for (bad in listOf("../escape.txt", "a/../../escape.txt", "/etc/escape.txt", "a\\..\\..\\escape.txt", "")) {
            escaping.extract(fileEntry(bad, "evil"))
        }
        val summary = escaping.finish()
        assertEquals(5, summary.failed)
        assertTrue(summary.issues.all { it.code == ExtractIssueCode.UNSAFE_PATH })
        assertFalse(File(sandbox, "escape.txt").exists())
        assertFalse(File(outside, "escape.txt").exists())
        assertEquals(1, x.finish().files)
    }

    @Test
    fun neverWritesThroughAnExtractedSymlink() = runTest {
        val x = extractor()
        x.extract(linkEntry("link", outside.absolutePath))
        x.extract(fileEntry("link/payload.txt", "evil"))
        x.extract(dirEntry("link/sub"))
        val summary = x.finish()
        assertTrue(Files.isSymbolicLink(File(root, "link").toPath()))
        assertEquals(outside.absolutePath, Files.readSymbolicLink(File(root, "link").toPath()).toString())
        assertFalse(File(outside, "payload.txt").exists())
        assertFalse(File(outside, "sub").exists())
        assertEquals(2, summary.issues.count { it.code == ExtractIssueCode.THROUGH_SYMLINK })
    }

    @Test
    fun neverWritesThroughAPreExistingSymlink() = runTest {
        Files.createSymbolicLink(File(root, "pre").toPath(), outside.toPath())
        val x = extractor()
        x.extract(fileEntry("pre/payload.txt", "evil"))
        val summary = x.finish()
        assertFalse(File(outside, "payload.txt").exists())
        assertEquals(ExtractIssueCode.THROUGH_SYMLINK, summary.issues.single().code)
    }

    @Test
    fun overwriteReplacesAFinalSymlinkInsteadOfItsTarget() = runTest {
        val victim = File(outside, "victim.txt").apply { writeText("original") }
        Files.createSymbolicLink(File(root, "file.txt").toPath(), victim.toPath())
        val x = extractor(ConflictPolicy.OVERWRITE)
        x.extract(fileEntry("file.txt", "restored"))
        x.finish()
        assertEquals("original", victim.readText())
        assertFalse(Files.isSymbolicLink(File(root, "file.txt").toPath()))
        assertEquals("restored", File(root, "file.txt").readText())
    }

    @Test
    fun conflictPolicies() = runTest {
        File(root, "doc.txt").writeText("mine")

        val skip = extractor(ConflictPolicy.SKIP)
        skip.extract(fileEntry("doc.txt", "backup"))
        val skipped = skip.finish()
        assertEquals("mine", File(root, "doc.txt").readText())
        assertEquals(1, skipped.skipped)
        assertEquals(ExtractIssueCode.SKIPPED_EXISTING, skipped.issues.single().code)

        val keepBoth = extractor(ConflictPolicy.KEEP_BOTH)
        keepBoth.extract(fileEntry("doc.txt", "backup"))
        keepBoth.extract(fileEntry("doc.txt", "backup again"))
        keepBoth.finish()
        assertEquals("mine", File(root, "doc.txt").readText())
        assertEquals("backup", File(root, "doc (2).txt").readText())
        assertEquals("backup again", File(root, "doc (3).txt").readText())

        val overwrite = extractor(ConflictPolicy.OVERWRITE)
        overwrite.extract(fileEntry("doc.txt", "backup"))
        overwrite.finish()
        assertEquals("backup", File(root, "doc.txt").readText())
    }

    @Test
    fun directoriesAreMergedNeverReplaced() = runTest {
        File(root, "dir").mkdirs()
        File(root, "dir/keep.txt").writeText("keep")
        val x = extractor(ConflictPolicy.OVERWRITE)
        x.extract(dirEntry("dir"))
        x.extract(fileEntry("dir", "a file where a folder is"))
        x.extract(fileEntry("dir/new.txt", "new"))
        val summary = x.finish()
        assertTrue(File(root, "dir").isDirectory)
        assertEquals("keep", File(root, "dir/keep.txt").readText())
        assertEquals("new", File(root, "dir/new.txt").readText())
        assertTrue(summary.issues.any { it.code == ExtractIssueCode.DIRECTORY_IN_THE_WAY })
    }

    @Test
    fun digestMismatchNeverReplacesTheExistingFile() = runTest {
        File(root, "data.bin").writeText("good old data")
        val x = extractor(ConflictPolicy.OVERWRITE)
        x.extract(fileEntry("data.bin", "corrupted", digest = RestoreFixtures.sha256("expected".toByteArray())))
        val summary = x.finish()
        assertEquals("good old data", File(root, "data.bin").readText())
        assertEquals(1, summary.failed)
        assertEquals(ExtractIssueCode.DIGEST_MISMATCH, summary.issues.single().code)
        assertTrue(root.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun hardLinksPointAtFilesOfThisExtractionOnly() = runTest {
        val x = extractor()
        x.extract(fileEntry("a.txt", "shared"))
        x.extract(hardLinkEntry("b.txt", "a.txt"))
        x.extract(hardLinkEntry("c.txt", "never-extracted.txt"))
        val summary = x.finish()
        assertEquals("shared", File(root, "b.txt").readText())
        assertEquals(Files.getAttribute(File(root, "a.txt").toPath(), "unix:ino"), Files.getAttribute(File(root, "b.txt").toPath(), "unix:ino"))
        assertFalse(File(root, "c.txt").exists())
        assertEquals(ExtractIssueCode.HARDLINK_TARGET_MISSING, summary.issues.single().code)
    }

    @Test
    fun specialFilesAreSkipped() = runTest {
        val x = extractor()
        x.extract(ExtractEntry(ArchiveEntryInfo("fifo", ArchiveEntryType.OTHER, 0, null, EntryMetadata()), null))
        val summary = x.finish()
        assertFalse(File(root, "fifo").exists())
        assertEquals(ExtractIssueCode.SPECIAL_SKIPPED, summary.issues.single().code)
    }

    @Test
    fun symlinkTargetsAreStoredVerbatim() = runTest {
        val x = extractor()
        x.extract(linkEntry("rel", "../sibling/file"))
        x.finish()
        assertEquals(Paths.get("../sibling/file"), Files.readSymbolicLink(File(root, "rel").toPath()))
    }

    @Test
    fun aLaterSymlinkAtTheSamePathDropsTheEarlierFilesMetadata() = runTest {
        val applied = ArrayList<MetadataOp>()
        val rootLike = object : TargetFileSystem by target {
            override val canSetOwnership: Boolean = true
            override suspend fun applyMetadata(ops: List<MetadataOp>, ownership: Boolean): List<String> {
                applied += ops
                return emptyList()
            }
        }
        val x = extractor(ConflictPolicy.OVERWRITE) { RoutedPath(rootLike, it) }
        x.extract(
            ExtractEntry(
                ArchiveEntryInfo("x/a", ArchiveEntryType.FILE, 1, null, EntryMetadata(mode = 0xDFF, uid = 0, gid = 0)),
                { ByteArrayInputStream(byteArrayOf(1)) },
            ),
        )
        x.extract(
            ExtractEntry(ArchiveEntryInfo("x/a", ArchiveEntryType.SYMLINK, 0, outside.absolutePath, EntryMetadata(uid = 0, gid = 0)), null),
        )
        x.finish()
        val ops = applied.filter { it.path == "x/a" }
        assertEquals(1, ops.size)
        assertEquals(TargetNodeType.SYMLINK, ops.single().type)
        // The symlink op can never produce a chmod.
        assertFalse(RestoreScripts.metadataScript(ops, ownership = true, mtime = true).contains("chmod"))
        assertTrue(Files.isSymbolicLink(File(root, "x/a").toPath()))
    }
}
