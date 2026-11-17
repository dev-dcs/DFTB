package ca.devdcs.backup.core.retention

import ca.devdcs.backup.core.storage.RemoteEntry
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StoragePaths
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class RemoteTreeDeleterTest {

    /** In-memory tree whose delete refuses non-empty folders, like the real contract. */
    private class TreeSession(paths: List<String>) : StorageSession {
        val dirs = sortedSetOf<String>()
        val files = sortedSetOf<String>()
        val deleteOrder = ArrayList<String>()

        init {
            paths.forEach { p ->
                val segs = StoragePaths.segments(p)
                for (i in 1 until segs.size) dirs += segs.take(i).joinToString("/")
                if (p.endsWith("/")) dirs += segs.joinToString("/") else files += segs.joinToString("/")
            }
        }

        override val destination = Destination(id = "d", name = "d", type = DestinationType.LOCAL_INTERNAL, createdAt = 0L, updatedAt = 0L)
        override suspend fun spaceInfo(): SpaceInfo? = null

        override suspend fun list(path: String): List<RemoteEntry> {
            val base = StoragePaths.normalize(path)
            if (base !in dirs) throw StorageException(StorageException.Reason.NOT_FOUND, base)
            val children = (dirs + files).filter { StoragePaths.parent(it) == base && it != base }
            return children.map { RemoteEntry(path = "/absolute/odd/$it", name = StoragePaths.name(it), isDirectory = it in dirs, sizeBytes = null, modifiedAtMillis = null) }
        }

        override suspend fun stat(path: String): RemoteEntry? {
            val p = StoragePaths.normalize(path)
            return when (p) {
                in dirs -> RemoteEntry(p, StoragePaths.name(p), true, null, null)
                in files -> RemoteEntry(p, StoragePaths.name(p), false, 1L, null)
                else -> null
            }
        }

        override suspend fun mkdirs(path: String) = Unit
        override suspend fun openWrite(path: String, append: Boolean): OutputStream = ByteArrayOutputStream()
        override suspend fun openRead(path: String, offset: Long): InputStream = ByteArrayInputStream(ByteArray(0))

        override suspend fun delete(path: String) {
            val p = StoragePaths.normalize(path)
            if (p in dirs) {
                if ((dirs + files).any { StoragePaths.parent(it) == p && it != p }) {
                    throw StorageException(StorageException.Reason.PROTOCOL, "Folder not empty: $p")
                }
                dirs -= p
            } else {
                files -= p
            }
            deleteOrder += p
        }

        override suspend fun rename(from: String, to: String) = Unit
        override fun close() = Unit
    }

    @Test
    fun deletesNestedTreeBottomUpAndLeavesSiblings() = runTest {
        val session = TreeSession(
            listOf(
                "job/manifest.json",
                "job/apps/a.tar.zst",
                "job/apps/deep/x/y.bin",
                "job/empty/",
                "other/keep.txt",
                "jobber/keep.txt",
            ),
        )
        val removed = RemoteTreeDeleter.deleteTree(session, "job")
        assertEquals(8, removed) // 3 files + job, apps, deep, x, empty
        assertFalse("job" in session.dirs)
        assertTrue(session.files.containsAll(listOf("other/keep.txt", "jobber/keep.txt")))
        assertEquals("job", session.deleteOrder.last())
    }

    @Test
    fun missingPathIsNotAnError() = runTest {
        val session = TreeSession(listOf("a/b.txt"))
        assertEquals(0, RemoteTreeDeleter.deleteTree(session, "nope"))
    }

    @Test
    fun singleFileIsDeleted() = runTest {
        val session = TreeSession(listOf("a/b.txt"))
        assertEquals(1, RemoteTreeDeleter.deleteTree(session, "a/b.txt"))
        assertTrue(session.files.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesDestinationBase() = runTest {
        RemoteTreeDeleter.deleteTree(TreeSession(listOf("a/b.txt")), "/")
    }
}
