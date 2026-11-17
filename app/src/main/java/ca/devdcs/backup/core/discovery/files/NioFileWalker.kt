package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-process walker for paths the app may read itself (own dirs, or shared storage with All files
 * access). Cheapest option: no process, no binder. Symlinks below the root are never followed
 * (attributes read with NOFOLLOW_LINKS); a symlinked root such as `/sdcard` is resolved once.
 * Cycle-safe through bind mounts by remembering each directory's (device, inode) file key.
 */
@Singleton
class NioFileWalker @Inject constructor(
    @IoDispatcher private val io: CoroutineDispatcher,
) : FileWalker {

    override val kind: WalkerKind = WalkerKind.NIO

    override fun walk(spec: WalkSpec): Flow<WalkEvent> = flow {
        val root: Path = try {
            Paths.get(spec.rootLocation)
        } catch (error: InvalidPathException) {
            emit(WalkEvent.RootUnavailable(RootProblem.NOT_FOUND, error.message ?: spec.rootLocation))
            return@flow
        }
        val rootAttrs = try {
            Files.readAttributes(root, BasicFileAttributes::class.java)
        } catch (error: NoSuchFileException) {
            emit(WalkEvent.RootUnavailable(RootProblem.NOT_FOUND, spec.rootLocation))
            return@flow
        } catch (error: IOException) {
            emit(WalkEvent.RootUnavailable(RootProblem.NOT_READABLE, error.message ?: spec.rootLocation))
            return@flow
        } catch (error: SecurityException) {
            emit(WalkEvent.RootUnavailable(RootProblem.NOT_READABLE, error.message ?: spec.rootLocation))
            return@flow
        }
        if (rootAttrs.isRegularFile) {
            val name = root.fileName?.toString() ?: spec.rootLocation
            emit(WalkEvent.File(name, root.toString(), rootAttrs.size(), rootAttrs.lastModifiedTime().toMillis()))
            return@flow
        }
        if (!rootAttrs.isDirectory) {
            emit(WalkEvent.RootUnavailable(RootProblem.NOT_A_DIRECTORY, spec.rootLocation))
            return@flow
        }

        val visited = HashSet<Any>()
        visited.add(rootAttrs.fileKey() ?: root.toAbsolutePath().normalize().toString())
        val stack = ArrayDeque<Pair<Path, String>>()
        stack.addLast(root to "")
        val batch = ArrayList<WalkEvent>()
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (dir, rel) = stack.removeLast()
            emit(WalkEvent.Directory(rel))
            batch.clear()
            // List without emitting inside the try, so downstream exceptions are never caught here.
            val failure: WalkEvent? = try {
                Files.newDirectoryStream(dir).use { stream ->
                    for (child in stream) {
                        currentCoroutineContext().ensureActive()
                        val name = child.fileName?.toString() ?: continue
                        val childRel = if (rel.isEmpty()) name else "$rel/$name"
                        val attrs = try {
                            Files.readAttributes(child, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                        } catch (error: IOException) {
                            batch += WalkEvent.Unreadable(childRel, error.message ?: error.javaClass.simpleName)
                            continue
                        }
                        when {
                            attrs.isSymbolicLink -> Unit
                            attrs.isDirectory -> {
                                if (childRel in spec.hiddenDirs) {
                                    batch += WalkEvent.Unreadable(childRel, HIDDEN_BY_SCOPED_STORAGE)
                                } else if (spec.recursive && !spec.exclusions.excludesDirectory(childRel)) {
                                    val key = attrs.fileKey() ?: child.toAbsolutePath().normalize().toString()
                                    if (visited.add(key)) stack.addLast(child to childRel)
                                }
                            }
                            attrs.isRegularFile ->
                                batch += WalkEvent.File(childRel, child.toString(), attrs.size(), attrs.lastModifiedTime().toMillis())
                            else -> Unit // sockets, fifos, device nodes: never backup payload
                        }
                    }
                }
                null
            } catch (error: DirectoryIteratorException) {
                WalkEvent.Unreadable(rel, error.cause?.message ?: "directory listing failed")
            } catch (error: AccessDeniedException) {
                WalkEvent.Unreadable(rel, "Permission denied")
            } catch (error: NotDirectoryException) {
                WalkEvent.Unreadable(rel, "Not a directory")
            } catch (error: IOException) {
                WalkEvent.Unreadable(rel, error.message ?: error.javaClass.simpleName)
            } catch (error: SecurityException) {
                WalkEvent.Unreadable(rel, error.message ?: "SecurityException")
            }
            if (failure != null && rel.isEmpty()) {
                emit(WalkEvent.RootUnavailable(RootProblem.NOT_READABLE, spec.rootLocation))
                return@flow
            }
            for (event in batch) emit(event)
            if (failure != null) emit(failure)
        }
    }.flowOn(io)

    private companion object {
        const val HIDDEN_BY_SCOPED_STORAGE = "Hidden by scoped storage; needs Shizuku or Root"
    }
}
