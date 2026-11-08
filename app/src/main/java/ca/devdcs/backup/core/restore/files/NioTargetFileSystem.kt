package ca.devdcs.backup.core.restore.files

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.UUID

/**
 * A target folder the app process can write directly (app storage, all-files-access shared
 * storage, a USB volume path). Pure java.nio, so the extractor's safety rules are tested against
 * a real filesystem on the JVM. Every operation uses NOFOLLOW_LINKS semantics; replacing a path
 * renames over it, which replaces a symlink itself rather than the file it points to.
 */
class NioTargetFileSystem(
    rootDir: File,
    private val io: CoroutineDispatcher,
    override val label: String = rootDir.path,
) : TargetFileSystem {

    private val root: Path = rootDir.toPath().toAbsolutePath().normalize()

    override val supportsSymlinks: Boolean = true
    override val supportsHardLinks: Boolean = true
    override val canSetOwnership: Boolean = false
    override val canSetMtime: Boolean = true

    private fun resolve(path: String): Path {
        if (path.isEmpty()) return root
        val resolved = root.resolve(path).normalize()
        if (!resolved.startsWith(root) || resolved == root) throw IOException("Path escapes the restore root: $path")
        return resolved
    }

    override suspend fun lstat(path: String): TargetNode? = withContext(io) {
        try {
            val attrs = Files.readAttributes(resolve(path), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val type = when {
                attrs.isSymbolicLink -> TargetNodeType.SYMLINK
                attrs.isDirectory -> TargetNodeType.DIRECTORY
                attrs.isRegularFile -> TargetNodeType.FILE
                else -> TargetNodeType.OTHER
            }
            TargetNode(type, if (type == TargetNodeType.FILE) attrs.size() else null)
        } catch (e: NoSuchFileException) {
            null
        }
    }

    override suspend fun mkdir(path: String) {
        withContext(io) { Files.createDirectory(resolve(path)) }
    }

    override suspend fun openTemp(dir: String, finalName: String): TempWrite = withContext(io) {
        val temp = resolve(dir).resolve(".dftb-restore-" + UUID.randomUUID() + ".part")
        val out = Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        TempWrite(root.relativize(temp).toString().replace(File.separatorChar, '/'), out)
    }

    override suspend fun commit(temp: TempWrite, path: String, replace: Boolean) {
        withContext(io) {
            val from = resolve(temp.tempPath)
            val to = resolve(path)
            if (replace) {
                // rename(2): atomic, replaces a file or the symlink itself, never its target.
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } else {
                Files.move(from, to, LinkOption.NOFOLLOW_LINKS)
            }
        }
    }

    override suspend fun discard(temp: TempWrite) {
        withContext(io) {
            runCatching { temp.stream.close() }
            runCatching { Files.deleteIfExists(resolve(temp.tempPath)) }
        }
    }

    override suspend fun deleteFile(path: String) {
        withContext(io) {
            val p = resolve(path)
            val attrs = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (attrs.isDirectory && !attrs.isSymbolicLink) throw IOException("Refusing to delete directory $path")
            Files.delete(p)
        }
    }

    override suspend fun symlink(path: String, target: String) {
        withContext(io) { Files.createSymbolicLink(resolve(path), Paths.get(target)) }
    }

    override suspend fun hardLink(path: String, existing: String) {
        withContext(io) {
            val source = resolve(existing)
            if (Files.isSymbolicLink(source)) throw IOException("Hard link source $existing is a symlink")
            Files.createLink(resolve(path), source)
        }
    }

    override suspend fun applyMetadata(ops: List<MetadataOp>, ownership: Boolean): List<String> = withContext(io) {
        val failures = ArrayList<String>()
        for (op in ops) {
            val millis = op.modifiedAtMillis ?: continue
            if (op.type != TargetNodeType.FILE && op.type != TargetNodeType.DIRECTORY) continue
            try {
                val p = resolve(op.path)
                // setLastModifiedTime follows links: only touch what is still a real file/dir.
                val attrs = Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attrs.isSymbolicLink) continue
                Files.setLastModifiedTime(p, FileTime.fromMillis(millis))
            } catch (e: IOException) {
                failures += "${op.path}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        failures
    }

    override suspend fun freeBytes(): Long? = withContext(io) { root.toFile().usableSpace.takeIf { it > 0L } }

    override fun close() = Unit
}
