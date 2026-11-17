package ca.devdcs.backup.core.discovery.files

import android.content.ContentUris
import android.content.Context
import android.os.Environment
import android.provider.BaseColumns
import android.provider.MediaStore
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.FileCategory
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
 * Standard-tier fallback for media categories when the app has no All files access: queries the
 * MediaStore index (images/video/audio the user granted) restricted to the root's RELATIVE_PATH.
 * Only roots on primary shared storage are supported, since RELATIVE_PATH is volume-relative;
 * the query targets VOLUME_EXTERNAL_PRIMARY so SD-card rows are never attributed to primary.
 * Entries' locations are `content://media/...` URIs, readable with the same grant. Pending and
 * trashed items are excluded by MediaStore's default query behaviour.
 */
@Singleton
class MediaStoreFileWalker @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : FileWalker {

    override val kind: WalkerKind = WalkerKind.MEDIA_STORE

    override fun walk(spec: WalkSpec): Flow<WalkEvent> = flow {
        val primary = LocationClassifier.normalize(Environment.getExternalStorageDirectory().absolutePath)
        val relativeRoot = mediaRelativeRoot(LocationClassifier.normalize(spec.rootLocation), primary)
        if (relativeRoot == null) {
            emit(WalkEvent.RootUnavailable(RootProblem.NOT_READABLE, "Not on primary shared storage: ${spec.rootLocation}"))
            return@flow
        }
        val mediaTypes = spec.mediaCategories.mapNotNull(::mediaTypeOf).sorted()
        if (mediaTypes.isEmpty()) return@flow

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = buildSelection(mediaTypes.size, spec.recursive, relativeRoot.isEmpty())
        val args = buildArgs(mediaTypes, relativeRoot, spec.recursive)
        val cursor = try {
            context.contentResolver.query(collection, PROJECTION, selection, args, "${MediaStore.MediaColumns.RELATIVE_PATH} ASC")
        } catch (error: SecurityException) {
            emit(WalkEvent.RootUnavailable(RootProblem.NOT_READABLE, error.message ?: "Media permission missing"))
            return@flow
        }
        if (cursor == null) {
            emit(WalkEvent.RootUnavailable(RootProblem.TOOL_FAILED, "MediaStore returned no cursor"))
            return@flow
        }
        cursor.use { c ->
            val iId = c.getColumnIndexOrThrow(BaseColumns._ID)
            val iRel = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            // Directory (below root) -> excluded?; also drives the dirsVisited count.
            val seenDirs = HashMap<String, Boolean>()
            emit(WalkEvent.Directory(""))
            seenDirs[""] = false
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val name = c.getString(iName) ?: continue
                val relPath = c.getString(iRel).orEmpty()
                val dirBelowRoot = relPath.removePrefix(relativeRoot).trim('/')
                val excluded = seenDirs[dirBelowRoot] ?: spec.exclusions.excludesDirectory(dirBelowRoot).also { isExcluded ->
                    seenDirs[dirBelowRoot] = isExcluded
                    if (!isExcluded) emit(WalkEvent.Directory(dirBelowRoot))
                }
                if (excluded) continue
                val rel = if (dirBelowRoot.isEmpty()) name else "$dirBelowRoot/$name"
                val id = c.getLong(iId)
                val size = if (c.isNull(iSize)) 0L else c.getLong(iSize)
                val modifiedSeconds = if (c.isNull(iMod)) 0L else c.getLong(iMod)
                emit(WalkEvent.File(rel, ContentUris.withAppendedId(collection, id).toString(), size, modifiedSeconds * 1000))
            }
        }
    }.flowOn(io)

    companion object {
        private val PROJECTION = arrayOf(
            BaseColumns._ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )

        /**
         * RELATIVE_PATH prefix ("DCIM/", "" for the volume root) of [root] on [primary], or null
         * when [root] is not on primary shared storage. Pure; unit-tested.
         */
        fun mediaRelativeRoot(root: String, primary: String): String? {
            val aliases = listOf(primary, "/sdcard", "/storage/self/primary")
            for (alias in aliases) {
                if (root == alias) return ""
                if (root.startsWith("$alias/")) return root.substring(alias.length + 1).trimEnd('/') + "/"
            }
            return null
        }

        fun mediaTypeOf(category: FileCategory): Int? = when (category) {
            FileCategory.IMAGES -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            FileCategory.VIDEOS -> MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            FileCategory.AUDIO -> MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
            else -> null
        }

        /** SQL selection: media types IN (...), plus a RELATIVE_PATH equality or LIKE-prefix test. */
        fun buildSelection(mediaTypeCount: Int, recursive: Boolean, volumeRoot: Boolean): String {
            val types = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${List(mediaTypeCount) { "?" }.joinToString(",")})"
            if (volumeRoot && recursive) return types
            val path = if (recursive) {
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"
            } else {
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            }
            return "$types AND $path"
        }

        fun buildArgs(mediaTypes: List<Int>, relativeRoot: String, recursive: Boolean): Array<String> {
            val args = mediaTypes.map(Int::toString).toMutableList()
            if (relativeRoot.isEmpty() && recursive) return args.toTypedArray()
            args += if (recursive) escapeLike(relativeRoot) + "%" else relativeRoot
            return args.toTypedArray()
        }

        /** Escapes LIKE wildcards with '\' (paired with `ESCAPE '\'`). */
        fun escapeLike(value: String): String = buildString {
            for (ch in value) {
                if (ch == '%' || ch == '_' || ch == '\\') append('\\')
                append(ch)
            }
        }
    }
}
