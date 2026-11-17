package ca.devdcs.backup.core.restore.files

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/**
 * A restore target inside a Storage Access Framework tree the user granted (SD card, USB stick,
 * cloud provider folder). SAF has no symlinks, hard links, ownership or settable mtime, so those
 * are reported unsupported and the extractor records them as issues instead of faking them.
 * Paths are resolved name by name from the tree root, never by provider-supplied paths.
 */
class SafTargetFileSystem(
    context: Context,
    private val treeUri: Uri,
    private val io: CoroutineDispatcher,
    override val label: String = treeUri.toString(),
) : TargetFileSystem {

    private val resolver: ContentResolver = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    override val supportsSymlinks: Boolean = false
    override val supportsHardLinks: Boolean = false
    override val canSetOwnership: Boolean = false
    override val canSetMtime: Boolean = false

    private data class Doc(val id: String, val isDir: Boolean, val size: Long?)

    /** Resolved documents by relative path (directories mostly); invalidated on every change. */
    private val cache = HashMap<String, Doc>()

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)

    private fun findChild(parentId: String, name: String): Doc? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) != name) continue
                val isDir = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (cursor.isNull(3)) null else cursor.getLong(3)
                return Doc(cursor.getString(0), isDir, size)
            }
        } ?: throw IOException("Cannot list $label (permission revoked?)")
        return null
    }

    private fun resolve(path: String): Doc? {
        if (path.isEmpty()) return Doc(rootDocId, isDir = true, size = null)
        cache[path]?.let { return it }
        val segments = path.split('/')
        var parent = Doc(rootDocId, isDir = true, size = null)
        var prefix = ""
        for (segment in segments) {
            prefix = if (prefix.isEmpty()) segment else "$prefix/$segment"
            val next = cache[prefix] ?: findChild(parent.id, segment)?.also { if (it.isDir) cache[prefix] = it } ?: return null
            if (prefix != path && !next.isDir) return null
            parent = next
        }
        return parent
    }

    private fun parentAndName(path: String): Pair<Doc, String> {
        val parentPath = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val parent = resolve(parentPath)?.takeIf { it.isDir } ?: throw FileNotFoundException("Missing folder $parentPath in $label")
        return parent to name
    }

    override suspend fun lstat(path: String): TargetNode? = withContext(io) {
        resolve(path)?.let { TargetNode(if (it.isDir) TargetNodeType.DIRECTORY else TargetNodeType.FILE, it.size) }
    }

    override suspend fun mkdir(path: String) {
        withContext(io) {
            val (parent, name) = parentAndName(path)
            val created = DocumentsContract.createDocument(resolver, docUri(parent.id), DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: throw IOException("Provider refused to create folder $path")
            val id = DocumentsContract.getDocumentId(created)
            val actual = findChild(parent.id, name)
            if (actual == null || actual.id != id) {
                // The provider renamed it ("name (1)"): never restore into a folder we did not ask for.
                DocumentsContract.deleteDocument(resolver, created)
                throw IOException("Provider renamed folder $path")
            }
            cache[path] = Doc(id, isDir = true, size = null)
        }
    }

    override suspend fun openTemp(dir: String, finalName: String): TempWrite = withContext(io) {
        val parent = resolve(dir)?.takeIf { it.isDir } ?: throw FileNotFoundException("Missing folder $dir in $label")
        val uri = DocumentsContract.createDocument(resolver, docUri(parent.id), MIME_BINARY, ".dftb-restore-" + UUID.randomUUID() + ".part")
            ?: throw IOException("Provider refused to create a file in $dir")
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Provider returned no stream for $dir")
        TempWrite(uri.toString(), out)
    }

    override suspend fun commit(temp: TempWrite, path: String, replace: Boolean) {
        withContext(io) {
            val (parent, name) = parentAndName(path)
            val existing = findChild(parent.id, name)
            if (existing != null) {
                if (!replace || existing.isDir) throw IOException("$path already exists")
                DocumentsContract.deleteDocument(resolver, docUri(existing.id))
            }
            cache.remove(path)
            val renamed = DocumentsContract.renameDocument(resolver, Uri.parse(temp.tempPath), name)
                ?: throw IOException("Provider refused to rename into $path")
            val actual = findChild(parent.id, name)
            if (actual == null || actual.id != DocumentsContract.getDocumentId(renamed)) {
                throw IOException("Provider stored $path under another name")
            }
        }
    }

    override suspend fun discard(temp: TempWrite) {
        withContext(io) {
            runCatching { temp.stream.close() }
            runCatching { DocumentsContract.deleteDocument(resolver, Uri.parse(temp.tempPath)) }
        }
    }

    override suspend fun deleteFile(path: String) {
        withContext(io) {
            val doc = resolve(path) ?: return@withContext
            if (doc.isDir) throw IOException("Refusing to delete folder $path")
            DocumentsContract.deleteDocument(resolver, docUri(doc.id))
            cache.remove(path)
        }
    }

    override suspend fun symlink(path: String, target: String): Unit = throw IOException("Symbolic links are not supported in $label")

    override suspend fun hardLink(path: String, existing: String): Unit = throw IOException("Hard links are not supported in $label")

    override suspend fun applyMetadata(ops: List<MetadataOp>, ownership: Boolean): List<String> = emptyList()

    override suspend fun freeBytes(): Long? = null

    override fun close() {
        cache.clear()
    }

    private companion object {
        const val MIME_BINARY = "application/octet-stream"
    }
}
