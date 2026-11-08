package ca.devdcs.backup.core.discovery.files

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walker for `content://` tree URIs from ACTION_OPEN_DOCUMENT_TREE (USB OTG, SD cards, cloud
 * providers, or any folder in Standard mode). One `DocumentsContract` children query per directory
 * with the full projection, so each folder costs a single provider round trip. Cycle-safe by
 * document id (some providers expose the same folder under several parents).
 */
@Singleton
class SafFileWalker @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : FileWalker {

    override val kind: WalkerKind = WalkerKind.SAF

    private data class Doc(val id: String, val name: String, val mime: String?, val size: Long, val modified: Long) {
        val isDirectory: Boolean get() = mime == Document.MIME_TYPE_DIR
    }

    override fun walk(spec: WalkSpec): Flow<WalkEvent> = flow {
        val resolver = context.contentResolver
        val uri = Uri.parse(spec.rootLocation)
        // (tree URI or null for a plain document URI, root document id)
        val (treeUri, rootId) = try {
            if (DocumentsContract.isTreeUri(uri)) {
                val id = if (DocumentsContract.isDocumentUri(context, uri)) {
                    DocumentsContract.getDocumentId(uri)
                } else {
                    DocumentsContract.getTreeDocumentId(uri)
                }
                Pair<Uri?, String>(uri, id)
            } else {
                Pair<Uri?, String>(null, DocumentsContract.getDocumentId(uri))
            }
        } catch (error: IllegalArgumentException) {
            emit(WalkEvent.RootUnavailable(RootProblem.NOT_FOUND, error.message ?: spec.rootLocation))
            return@flow
        }

        val rootDoc = try {
            val docUri = if (treeUri != null) DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId) else uri
            querySingle(resolver, docUri)
        } catch (error: SecurityException) {
            emit(WalkEvent.RootUnavailable(RootProblem.NOT_READABLE, error.message ?: "SAF permission missing"))
            return@flow
        } catch (error: RuntimeException) {
            emit(WalkEvent.RootUnavailable(RootProblem.NOT_FOUND, error.message ?: spec.rootLocation))
            return@flow
        }
        if (rootDoc == null) {
            emit(WalkEvent.RootUnavailable(RootProblem.NOT_FOUND, spec.rootLocation))
            return@flow
        }
        if (!rootDoc.isDirectory || treeUri == null) {
            if (rootDoc.isDirectory) {
                emit(WalkEvent.RootUnavailable(RootProblem.NOT_A_DIRECTORY, "Folder URI is not a tree URI"))
            } else {
                emit(WalkEvent.File(rootDoc.name.ifEmpty { rootDoc.id }, uri.toString(), rootDoc.size, rootDoc.modified))
            }
            return@flow
        }

        val visited = HashSet<String>()
        visited.add(rootDoc.id)
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootDoc.id to "")
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (docId, rel) = stack.removeLast()
            emit(WalkEvent.Directory(rel))
            val listing: List<Doc>? = try {
                queryChildren(resolver, treeUri, docId)
            } catch (error: SecurityException) {
                if (rel.isEmpty()) {
                    emit(WalkEvent.RootUnavailable(RootProblem.NOT_READABLE, error.message ?: "SAF permission missing"))
                    return@flow
                }
                emit(WalkEvent.Unreadable(rel, error.message ?: "SecurityException"))
                continue
            } catch (error: RuntimeException) {
                // Providers throw IllegalArgumentException / IllegalStateException for vanished docs.
                emit(WalkEvent.Unreadable(rel, error.message ?: error.javaClass.simpleName))
                continue
            }
            if (listing == null) {
                emit(WalkEvent.Unreadable(rel, "Document provider returned no listing"))
                continue
            }
            for (child in listing) {
                currentCoroutineContext().ensureActive()
                if (child.name.isEmpty()) continue
                val childRel = if (rel.isEmpty()) child.name else "$rel/${child.name}"
                if (child.isDirectory) {
                    if (spec.recursive && !spec.exclusions.excludesDirectory(childRel) && visited.add(child.id)) {
                        stack.addLast(child.id to childRel)
                    }
                } else {
                    val location = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.id).toString()
                    emit(WalkEvent.File(childRel, location, child.size, child.modified))
                }
            }
        }
    }.flowOn(io)

    private fun querySingle(resolver: ContentResolver, docUri: Uri): Doc? =
        resolver.query(docUri, PROJECTION, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) read(cursor) else null
        }

    /** Null when the provider answered with no cursor. */
    private fun queryChildren(resolver: ContentResolver, treeUri: Uri, parentId: String): List<Doc>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
            val out = ArrayList<Doc>(cursor.count.coerceAtLeast(0))
            while (cursor.moveToNext()) out.add(read(cursor))
            out
        }
    }

    private fun read(cursor: Cursor): Doc {
        val iId = cursor.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
        val iName = cursor.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
        val iMime = cursor.getColumnIndex(Document.COLUMN_MIME_TYPE)
        val iSize = cursor.getColumnIndex(Document.COLUMN_SIZE)
        val iMod = cursor.getColumnIndex(Document.COLUMN_LAST_MODIFIED)
        return Doc(
            id = if (iId >= 0) cursor.getString(iId).orEmpty() else "",
            name = if (iName >= 0) cursor.getString(iName).orEmpty() else "",
            mime = if (iMime >= 0) cursor.getString(iMime) else null,
            size = if (iSize >= 0 && !cursor.isNull(iSize)) cursor.getLong(iSize) else 0L,
            modified = if (iMod >= 0 && !cursor.isNull(iMod)) cursor.getLong(iMod) else 0L,
        )
    }

    private companion object {
        val PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
