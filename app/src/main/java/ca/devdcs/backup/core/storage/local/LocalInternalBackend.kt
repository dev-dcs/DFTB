package ca.devdcs.backup.core.storage.local

import android.content.Context
import android.os.StatFs
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.storage.RemoteEntry
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import ca.devdcs.backup.core.storage.StoragePaths
import ca.devdcs.backup.core.storage.StorageSession
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StorageBackend] for [DestinationType.LOCAL_INTERNAL]: a directory reachable through plain
 * `java.io.File` (app-specific storage, or any absolute path the app may write).
 *
 * Exists because it is the zero-setup destination the onboarding wizard creates and the fastest
 * path for local backups (no Binder round-trip per call, unlike SAF). When
 * [ca.devdcs.backup.domain.model.DestinationConfig.basePath] is null the app-specific external
 * files dir is used. Every path is confined to the base after symlink resolution.
 */
@Singleton
class LocalInternalBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StorageBackend {

    override val types: Set<DestinationType> = setOf(DestinationType.LOCAL_INTERNAL)

    override suspend fun connect(destination: Destination): StorageSession = withContext(io) {
        val base = resolveBase(destination)
        LocalStorageErrors.guard("open local destination ${base.path}") {
            if (!base.exists() && !base.mkdirs() && !base.isDirectory) {
                throw StorageException(Reason.PERMISSION, "Cannot create destination folder ${base.path}")
            }
            if (!base.isDirectory) {
                throw StorageException(Reason.MISCONFIGURED, "Destination path is not a folder: ${base.path}")
            }
            if (!base.canWrite()) {
                throw StorageException(Reason.PERMISSION, "Destination folder is not writable: ${base.path}")
            }
        }
        val canonicalBase = LocalStorageErrors.guard("resolve ${base.path}") { base.canonicalFile }
        LocalSession(destination, canonicalBase, io)
    }

    private fun resolveBase(destination: Destination): File {
        val configured = destination.config.basePath?.trim()?.takeIf { it.isNotEmpty() }
        if (configured != null) {
            val file = File(configured)
            if (!file.isAbsolute) {
                throw StorageException(Reason.MISCONFIGURED, "Local destination path must be absolute: $configured")
            }
            return file
        }
        return context.getExternalFilesDir(null)
            ?: throw StorageException(Reason.NOT_FOUND, "App-specific external storage is not available")
    }

    private class LocalSession(
        override val destination: Destination,
        private val base: File,
        private val io: CoroutineDispatcher,
    ) : StorageSession {

        private val basePath: String = base.path

        /** Resolves a session path to a File, refusing anything outside [base] (incl. via symlink). */
        private fun fileFor(path: String): File {
            val rel = StoragePaths.normalize(path)
            val file = if (rel.isEmpty()) base else File(base, rel)
            val canonical = file.canonicalPath
            if (!StoragePaths.isWithin(basePath, canonical)) {
                throw StorageException(Reason.PERMISSION, "Path resolves outside the destination: $path")
            }
            return file
        }

        private fun entryOf(rel: String, file: File): RemoteEntry = RemoteEntry(
            path = rel,
            name = file.name,
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isDirectory) null else file.length(),
            modifiedAtMillis = file.lastModified().takeIf { it > 0L },
        )

        override suspend fun spaceInfo(): SpaceInfo? = withContext(io) {
            LocalStorageErrors.guard("read free space") {
                val stat = StatFs(basePath)
                SpaceInfo(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes)
            }
        }

        override suspend fun list(path: String): List<RemoteEntry> = withContext(io) {
            LocalStorageErrors.guard("list $path") {
                val rel = StoragePaths.normalize(path)
                val dir = fileFor(rel)
                if (!dir.exists()) throw StorageException(Reason.NOT_FOUND, "Folder not found: $rel")
                if (!dir.isDirectory) throw StorageException(Reason.MISCONFIGURED, "Not a folder: $rel")
                val children = dir.listFiles()
                    ?: throw StorageException(Reason.PERMISSION, "Cannot list folder: $rel")
                children.sortedBy { it.name }.map { entryOf(StoragePaths.join(rel, it.name), it) }
            }
        }

        override suspend fun stat(path: String): RemoteEntry? = withContext(io) {
            LocalStorageErrors.guard("stat $path") {
                val rel = StoragePaths.normalize(path)
                val file = fileFor(rel)
                if (file.exists()) entryOf(rel, file) else null
            }
        }

        override suspend fun mkdirs(path: String) = withContext(io) {
            LocalStorageErrors.guard("create folder $path") { ensureDir(fileFor(path), path) }
        }

        private fun ensureDir(dir: File, label: String) {
            if (dir.isDirectory) return
            if (dir.exists()) throw StorageException(Reason.MISCONFIGURED, "A file is in the way of folder: $label")
            if (!dir.mkdirs() && !dir.isDirectory) {
                throw StorageException(Reason.PERMISSION, "Cannot create folder: $label")
            }
        }

        override suspend fun openWrite(path: String, append: Boolean): OutputStream = withContext(io) {
            LocalStorageErrors.guard("write $path") {
                val rel = StoragePaths.normalize(path)
                if (rel.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "Cannot write to the destination root itself")
                val file = fileFor(rel)
                file.parentFile?.let { ensureDir(it, StoragePaths.parent(rel)) }
                if (file.isDirectory) throw StorageException(Reason.MISCONFIGURED, "A folder is in the way of file: $rel")
                LocalStorageErrors.mapErrors(FileOutputStream(file, append), "write $rel")
            }
        }

        override suspend fun openRead(path: String, offset: Long): InputStream = withContext(io) {
            LocalStorageErrors.guard("read $path") {
                require(offset >= 0L) { "Negative read offset: $offset" }
                val rel = StoragePaths.normalize(path)
                val file = fileFor(rel)
                if (!file.isFile) throw StorageException(Reason.NOT_FOUND, "File not found: $rel")
                val stream = FileInputStream(file)
                try {
                    if (offset > 0L) stream.channel.position(offset)
                } catch (e: Exception) {
                    stream.close()
                    throw e
                }
                LocalStorageErrors.mapErrors(stream, "read $rel")
            }
        }

        override suspend fun delete(path: String) = withContext(io) {
            LocalStorageErrors.guard("delete $path") {
                val rel = StoragePaths.normalize(path)
                if (rel.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "Refusing to delete the destination root")
                val file = fileFor(rel)
                if (!file.exists()) return@guard
                if (file.isDirectory && !file.list().isNullOrEmpty()) {
                    throw StorageException(Reason.UNSUPPORTED, "Folder is not empty: $rel")
                }
                if (!file.delete() && file.exists()) {
                    throw StorageException(Reason.PERMISSION, "Cannot delete: $rel")
                }
            }
        }

        override suspend fun rename(from: String, to: String) = withContext(io) {
            LocalStorageErrors.guard("rename $from to $to") {
                val fromRel = StoragePaths.normalize(from)
                val toRel = StoragePaths.normalize(to)
                if (fromRel.isEmpty() || toRel.isEmpty()) {
                    throw StorageException(Reason.MISCONFIGURED, "Cannot rename the destination root")
                }
                if (fromRel == toRel) return@guard
                val src = fileFor(fromRel)
                val dst = fileFor(toRel)
                if (!src.exists()) throw StorageException(Reason.NOT_FOUND, "Source not found: $fromRel")
                dst.parentFile?.let { ensureDir(it, StoragePaths.parent(toRel)) }
                // rename(2) replaces an existing file atomically; only fall back when it refuses.
                if (src.renameTo(dst)) return@guard
                if (dst.exists()) {
                    if (dst.isDirectory) {
                        if (!dst.deleteRecursively()) throw StorageException(Reason.PERMISSION, "Cannot replace: $toRel")
                    } else if (!dst.delete()) {
                        throw StorageException(Reason.PERMISSION, "Cannot replace: $toRel")
                    }
                    if (src.renameTo(dst)) return@guard
                }
                // Different filesystem (EXDEV): copy then delete the source.
                DftbLog.d(TAG, "rename fell back to copy+delete for $fromRel")
                if (src.isDirectory) src.copyRecursively(dst, overwrite = true) else src.copyTo(dst, overwrite = true)
                if (!src.deleteRecursively()) {
                    throw StorageException(Reason.PERMISSION, "Copied but could not remove source: $fromRel")
                }
            }
        }

        override fun close() = Unit
    }

    private companion object {
        const val TAG = "LocalInternalBackend"
    }
}
