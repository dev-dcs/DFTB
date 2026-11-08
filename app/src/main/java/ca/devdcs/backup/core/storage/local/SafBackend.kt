package ca.devdcs.backup.core.storage.local

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [StorageBackend] for [DestinationType.LOCAL_SAF] and [DestinationType.USB_OTG]: a folder the
 * user picked with ACTION_OPEN_DOCUMENT_TREE, addressed through DocumentsContract + ContentResolver.
 *
 * Exists because scoped storage forbids raw file access to shared storage, SD cards and USB
 * drives; a persisted tree grant is the only sanctioned way in. DocumentFile is deliberately not
 * used: it hides failures behind nulls/false and re-queries on every call, whereas this
 * implementation surfaces every error as [StorageException] and caches resolved folder ids.
 *
 * [ca.devdcs.backup.domain.model.DestinationConfig.treeUri] is the granted tree;
 * `basePath` (optional) is a sub-folder inside it, created on connect. For USB OTG the volume
 * ([ca.devdcs.backup.domain.model.DestinationConfig.usbVolumeId], or the id encoded in the tree)
 * must be mounted read-write.
 */
@Singleton
class SafBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : StorageBackend {

    override val types: Set<DestinationType> = setOf(DestinationType.LOCAL_SAF, DestinationType.USB_OTG)

    private val storageManager: StorageManager by lazy {
        context.getSystemService(StorageManager::class.java)
            ?: throw StorageException(Reason.UNSUPPORTED, "StorageManager is unavailable")
    }

    override suspend fun connect(destination: Destination): StorageSession = withContext(io) {
        LocalStorageErrors.guard("open folder for ${destination.name}") {
            val raw = destination.config.treeUri?.trim().orEmpty()
            if (raw.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "No folder has been selected for this destination")
            val treeUri = Uri.parse(raw)
            if (!DocumentsContract.isTreeUri(treeUri)) {
                throw StorageException(Reason.MISCONFIGURED, "The saved folder reference is not a document tree")
            }
            requirePersistedGrant(treeUri)
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val encodedVolume = SafVolumes.volumeIdFromDocumentId(treeUri.authority, treeDocId)
            val volumeId = if (destination.type == DestinationType.USB_OTG) {
                val id = destination.config.usbVolumeId?.trim()?.takeIf { it.isNotEmpty() } ?: encodedVolume
                    ?: throw StorageException(Reason.MISCONFIGURED, "USB destination has no volume id")
                requireMounted(id)
                id
            } else {
                encodedVolume
            }
            val session = SafSession(destination, context.contentResolver, treeUri, volumeId, io)
            session.initBase(treeDocId, destination.config.basePath.orEmpty())
            session
        }
    }

    private fun requirePersistedGrant(treeUri: Uri) {
        val target = treeUri.toString()
        val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri.toString() == target }
            ?: throw StorageException(
                Reason.PERMISSION,
                "Access to the selected folder was revoked; pick the folder again",
            )
        if (!grant.isReadPermission || !grant.isWritePermission) {
            throw StorageException(Reason.PERMISSION, "The app only has read access to the selected folder; pick it again")
        }
    }

    private fun requireMounted(volumeId: String) {
        val volume = findVolume(volumeId)
            ?: throw StorageException(Reason.NOT_FOUND, "USB drive $volumeId is not connected")
        when (val state = volume.state) {
            Environment.MEDIA_MOUNTED -> Unit
            Environment.MEDIA_MOUNTED_READ_ONLY ->
                throw StorageException(Reason.PERMISSION, "USB drive $volumeId is mounted read-only")
            else -> throw StorageException(Reason.NOT_FOUND, "USB drive $volumeId is not mounted (state: $state)")
        }
    }

    private fun findVolume(volumeId: String): StorageVolume? =
        storageManager.storageVolumes.firstOrNull { SafVolumes.matches(volumeId, it.uuid, it.isPrimary) }

    /** Free/total of the volume [volumeId] via StatFs on its mount point, or null if unknown. */
    @Suppress("DEPRECATION")
    private fun volumeSpace(volumeId: String): SpaceInfo? {
        val volume = findVolume(volumeId) ?: return null
        val dir: File = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> volume.directory ?: return null
            volume.isPrimary -> Environment.getExternalStorageDirectory()
            else -> File("/storage/${volume.uuid ?: return null}")
        }
        return try {
            val stat = StatFs(dir.path)
            SpaceInfo(freeBytes = stat.availableBytes, totalBytes = stat.totalBytes)
        } catch (e: IllegalArgumentException) {
            DftbLog.d(TAG, "StatFs failed for volume $volumeId: ${e.message}")
            null
        }
    }

    /** One SAF document as returned by a cursor row. */
    private data class Doc(
        val id: String,
        val name: String,
        val mime: String?,
        val size: Long?,
        val modified: Long?,
    ) {
        val isDirectory: Boolean get() = mime == Document.MIME_TYPE_DIR
    }

    private inner class SafSession(
        override val destination: Destination,
        private val resolver: ContentResolver,
        private val treeUri: Uri,
        private val volumeId: String?,
        private val io: CoroutineDispatcher,
    ) : StorageSession {

        private lateinit var baseId: String

        /** Normalized relative folder path -> document id. Files are never cached (sizes change). */
        private val dirIds = HashMap<String, String>()

        fun initBase(treeDocId: String, basePath: String) {
            val root = queryDoc(treeDocId)
                ?: throw StorageException(Reason.NOT_FOUND, "The selected folder no longer exists")
            if (!root.isDirectory) throw StorageException(Reason.MISCONFIGURED, "The selected item is not a folder")
            var current = root.id
            for (seg in StoragePaths.segments(basePath)) current = childDir(current, seg, create = true)!!
            baseId = current
            dirIds[""] = current
        }

        private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

        private fun readDoc(c: Cursor): Doc {
            fun idx(col: String) = c.getColumnIndex(col)
            val iId = idx(Document.COLUMN_DOCUMENT_ID)
            val iName = idx(Document.COLUMN_DISPLAY_NAME)
            val iMime = idx(Document.COLUMN_MIME_TYPE)
            val iSize = idx(Document.COLUMN_SIZE)
            val iMod = idx(Document.COLUMN_LAST_MODIFIED)
            if (iId < 0) throw StorageException(Reason.PROTOCOL, "Document provider returned no document ids")
            return Doc(
                id = c.getString(iId),
                name = if (iName >= 0) c.getString(iName).orEmpty() else "",
                mime = if (iMime >= 0) c.getString(iMime) else null,
                size = if (iSize >= 0 && !c.isNull(iSize)) c.getLong(iSize) else null,
                modified = if (iMod >= 0 && !c.isNull(iMod)) c.getLong(iMod).takeIf { it > 0L } else null,
            )
        }

        private fun queryDoc(id: String): Doc? =
            resolver.query(docUri(id), PROJECTION, null, null, null)?.use { c ->
                if (c.moveToFirst()) readDoc(c) else null
            }

        private fun children(parentId: String): List<Doc> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val cursor = resolver.query(uri, PROJECTION, null, null, null)
                ?: throw StorageException(Reason.PROTOCOL, "Document provider did not answer the folder listing")
            return cursor.use { c ->
                val out = ArrayList<Doc>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) out.add(readDoc(c))
                out
            }
        }

        private fun findChild(parentId: String, name: String): Doc? = children(parentId).firstOrNull { it.name == name }

        /** Creates a document named exactly [name]; providers that de-duplicate names are corrected or rejected. */
        private fun createExact(parentId: String, mime: String, name: String): Doc {
            val uri = DocumentsContract.createDocument(resolver, docUri(parentId), mime, name)
                ?: throw StorageException(Reason.PROTOCOL, "Document provider refused to create $name")
            var doc = queryDoc(DocumentsContract.getDocumentId(uri))
                ?: throw StorageException(Reason.PROTOCOL, "Created document $name vanished")
            if (doc.name != name) {
                val renamed = DocumentsContract.renameDocument(resolver, docUri(doc.id), name)
                doc = queryDoc(renamed?.let { DocumentsContract.getDocumentId(it) } ?: doc.id) ?: doc
                if (doc.name != name) {
                    DocumentsContract.deleteDocument(resolver, docUri(doc.id))
                    throw StorageException(Reason.PROTOCOL, "Document provider changed the name $name to ${doc.name}")
                }
            }
            return doc
        }

        /** Id of folder [name] under [parentId]; null when missing and ![create]. */
        private fun childDir(parentId: String, name: String, create: Boolean): String? {
            val existing = findChild(parentId, name)
            if (existing != null) {
                if (!existing.isDirectory) throw StorageException(Reason.MISCONFIGURED, "A file is in the way of folder: $name")
                return existing.id
            }
            if (!create) return null
            return createExact(parentId, Document.MIME_TYPE_DIR, name).id
        }

        /** Document id of folder [rel] (normalized), creating missing folders when [create]. */
        private fun dirId(rel: String, create: Boolean): String? {
            dirIds[rel]?.let { return it }
            var current = baseId
            var prefix = ""
            for (seg in StoragePaths.segments(rel)) {
                prefix = if (prefix.isEmpty()) seg else "$prefix/$seg"
                val cached = dirIds[prefix]
                current = cached ?: (childDir(current, seg, create) ?: return null).also { dirIds[prefix] = it }
            }
            return current
        }

        /** The document at [rel] (normalized) or null; the base resolves to its own folder. */
        private fun resolve(rel: String): Doc? {
            if (rel.isEmpty()) return queryDoc(baseId)
            val parentId = dirId(StoragePaths.parent(rel), create = false) ?: return null
            return findChild(parentId, StoragePaths.name(rel))
        }

        private fun evict(rel: String) {
            dirIds.keys.removeAll { it == rel || it.startsWith("$rel/") }
        }

        private fun entry(rel: String, doc: Doc) = RemoteEntry(
            path = rel,
            name = doc.name,
            isDirectory = doc.isDirectory,
            sizeBytes = if (doc.isDirectory) null else doc.size,
            modifiedAtMillis = doc.modified,
        )

        override suspend fun spaceInfo(): SpaceInfo? = withContext(io) {
            LocalStorageErrors.guard("read free space") {
                volumeId?.let { volumeSpace(it) } ?: fstatvfsSpace()
            }
        }

        /** Last resort: statvfs on an fd of the base folder; most providers refuse directory fds. */
        private fun fstatvfsSpace(): SpaceInfo? = try {
            resolver.openFileDescriptor(docUri(baseId), "r")?.use { pfd ->
                val s = Os.fstatvfs(pfd.fileDescriptor)
                val block = if (s.f_frsize > 0L) s.f_frsize else s.f_bsize
                SpaceInfo(freeBytes = s.f_bavail * block, totalBytes = s.f_blocks * block)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.d(TAG, "Free space not reportable for this provider: ${e.javaClass.simpleName}")
            null
        }

        override suspend fun list(path: String): List<RemoteEntry> = withContext(io) {
            LocalStorageErrors.guard("list $path") {
                val rel = StoragePaths.normalize(path)
                val doc = resolve(rel) ?: throw StorageException(Reason.NOT_FOUND, "Folder not found: $rel")
                if (!doc.isDirectory) throw StorageException(Reason.MISCONFIGURED, "Not a folder: $rel")
                if (rel.isNotEmpty()) dirIds[rel] = doc.id
                children(doc.id).sortedBy { it.name }.map { entry(StoragePaths.join(rel, it.name), it) }
            }
        }

        override suspend fun stat(path: String): RemoteEntry? = withContext(io) {
            LocalStorageErrors.guard("stat $path") {
                val rel = StoragePaths.normalize(path)
                resolve(rel)?.let { entry(rel, it) }
            }
        }

        override suspend fun mkdirs(path: String) = withContext(io) {
            LocalStorageErrors.guard("create folder $path") {
                dirId(StoragePaths.normalize(path), create = true)
                Unit
            }
        }

        override suspend fun openWrite(path: String, append: Boolean): OutputStream = withContext(io) {
            LocalStorageErrors.guard("write $path") {
                val rel = StoragePaths.normalize(path)
                if (rel.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "Cannot write to the destination root itself")
                val parentId = dirId(StoragePaths.parent(rel), create = true)!!
                val name = StoragePaths.name(rel)
                val existing = findChild(parentId, name)
                if (existing?.isDirectory == true) throw StorageException(Reason.MISCONFIGURED, "A folder is in the way of file: $rel")
                val doc = existing ?: createExact(parentId, BINARY_MIME, name)
                // "wt" (not plain "w"): some providers do not truncate on "w", leaving stale tail bytes.
                val mode = if (append) "wa" else "wt"
                val out = resolver.openOutputStream(docUri(doc.id), mode)
                    ?: throw StorageException(Reason.PROTOCOL, "Document provider returned no output stream for $rel")
                LocalStorageErrors.mapErrors(out, "write $rel")
            }
        }

        override suspend fun openRead(path: String, offset: Long): InputStream = withContext(io) {
            LocalStorageErrors.guard("read $path") {
                require(offset >= 0L) { "Negative read offset: $offset" }
                val rel = StoragePaths.normalize(path)
                val doc = resolve(rel)?.takeIf { !it.isDirectory }
                    ?: throw StorageException(Reason.NOT_FOUND, "File not found: $rel")
                LocalStorageErrors.mapErrors(openAt(docUri(doc.id), offset, rel), "read $rel")
            }
        }

        /** Seeks the fd when it is a real file; pipe-backed providers fall back to skipping. */
        private fun openAt(uri: Uri, offset: Long, rel: String): InputStream {
            val pfd = resolver.openFileDescriptor(uri, "r")
                ?: throw StorageException(Reason.PROTOCOL, "Document provider returned no file descriptor for $rel")
            val direct = ParcelFileDescriptor.AutoCloseInputStream(pfd)
            if (offset == 0L) return direct
            try {
                direct.channel.position(offset)
                return direct
            } catch (e: IOException) {
                direct.close()
            }
            val stream = resolver.openInputStream(uri)
                ?: throw StorageException(Reason.PROTOCOL, "Document provider returned no input stream for $rel")
            try {
                skipFully(stream, offset, rel)
            } catch (e: Exception) {
                stream.close()
                throw e
            }
            return stream
        }

        private fun skipFully(stream: InputStream, offset: Long, rel: String) {
            var remaining = offset
            while (remaining > 0L) {
                val skipped = stream.skip(remaining)
                if (skipped > 0L) {
                    remaining -= skipped
                } else {
                    if (stream.read() < 0) throw StorageException(Reason.MISCONFIGURED, "Offset $offset is past the end of $rel")
                    remaining--
                }
            }
        }

        override suspend fun delete(path: String) = withContext(io) {
            LocalStorageErrors.guard("delete $path") {
                val rel = StoragePaths.normalize(path)
                if (rel.isEmpty()) throw StorageException(Reason.MISCONFIGURED, "Refusing to delete the destination root")
                val doc = resolve(rel) ?: return@guard
                // deleteDocument is recursive; the contract only allows removing empty folders.
                if (doc.isDirectory && children(doc.id).isNotEmpty()) {
                    throw StorageException(Reason.UNSUPPORTED, "Folder is not empty: $rel")
                }
                if (!DocumentsContract.deleteDocument(resolver, docUri(doc.id))) {
                    throw StorageException(Reason.PERMISSION, "Cannot delete: $rel")
                }
                evict(rel)
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
                val src = resolve(fromRel) ?: throw StorageException(Reason.NOT_FOUND, "Source not found: $fromRel")
                val fromParentId = dirId(StoragePaths.parent(fromRel), create = false)!!
                val toParentId = dirId(StoragePaths.parent(toRel), create = true)!!
                val toName = StoragePaths.name(toRel)

                // Overwrite semantics: SAF has no replace-on-rename, so remove the target first.
                findChild(toParentId, toName)?.takeIf { it.id != src.id }?.let { target ->
                    if (!DocumentsContract.deleteDocument(resolver, docUri(target.id))) {
                        throw StorageException(Reason.PERMISSION, "Cannot replace: $toRel")
                    }
                }
                evict(fromRel)
                evict(toRel)

                var movedId = src.id
                if (fromParentId != toParentId) {
                    movedId = try {
                        val moved = DocumentsContract.moveDocument(resolver, docUri(src.id), docUri(fromParentId), docUri(toParentId))
                            ?: throw UnsupportedOperationException("moveDocument returned null")
                        DocumentsContract.getDocumentId(moved)
                    } catch (e: UnsupportedOperationException) {
                        copyThenDelete(src, toParentId, toName, fromRel)
                    } catch (e: IllegalStateException) {
                        copyThenDelete(src, toParentId, toName, fromRel)
                    }
                }
                val current = queryDoc(movedId) ?: findChild(toParentId, src.name)
                    ?: throw StorageException(Reason.PROTOCOL, "Renamed document vanished: $fromRel")
                if (current.name != toName) {
                    val renamed = DocumentsContract.renameDocument(resolver, docUri(current.id), toName)
                    val finalId = renamed?.let { DocumentsContract.getDocumentId(it) } ?: current.id
                    val finalName = queryDoc(finalId)?.name
                    if (finalName != toName) {
                        throw StorageException(Reason.PROTOCOL, "Document provider renamed $fromRel to ${finalName ?: "?"} instead of $toName")
                    }
                }
            }
        }

        /** Cross-folder fallback when the provider cannot move: stream-copy a file, then delete the source. */
        private fun copyThenDelete(src: Doc, toParentId: String, toName: String, fromRel: String): String {
            if (src.isDirectory) throw StorageException(Reason.UNSUPPORTED, "This storage cannot move folders: $fromRel")
            DftbLog.d(TAG, "moveDocument unsupported; copying $fromRel")
            val target = createExact(toParentId, BINARY_MIME, toName)
            val input = resolver.openInputStream(docUri(src.id))
                ?: throw StorageException(Reason.PROTOCOL, "No input stream for $fromRel")
            input.use { inp ->
                val out = resolver.openOutputStream(docUri(target.id), "wt")
                    ?: throw StorageException(Reason.PROTOCOL, "No output stream for $toName")
                out.use { inp.copyTo(it, COPY_BUFFER) }
            }
            if (!DocumentsContract.deleteDocument(resolver, docUri(src.id))) {
                throw StorageException(Reason.PERMISSION, "Copied but could not remove source: $fromRel")
            }
            return target.id
        }

        override fun close() {
            dirIds.clear()
        }
    }

    private companion object {
        const val TAG = "SafBackend"
        const val BINARY_MIME = "application/octet-stream"
        const val COPY_BUFFER = 1 shl 16
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
