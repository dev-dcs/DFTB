package ca.devdcs.backup.core.discovery.files

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.FileCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only Android-facing source of "what may this app read right now": permission states, All
 * files access, legacy storage, the current elevation tier and SAF grants. Snapshotted once per
 * scan into a pure [AccessContext] so [WalkerSelector] stays unit-testable.
 */
@Singleton
class StorageAccessInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: CommandExecutorRouter,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    val ownPackage: String get() = context.packageName

    /** Absolute path of primary shared storage (normally `/storage/emulated/<user>`). */
    val primaryStoragePath: String get() = Environment.getExternalStorageDirectory().absolutePath

    suspend fun snapshot(): AccessContext {
        val executor = router.current()
        val privileged = executor.tier != ElevationTier.NONE && executor.isAvailable()
        val sdk = Build.VERSION.SDK_INT
        val allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        val readExternal = granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        val legacy = sdk <= Build.VERSION_CODES.Q && readExternal && Environment.isExternalStorageLegacy()
        val media = HashSet<FileCategory>()
        var partial = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (granted(Manifest.permission.READ_MEDIA_IMAGES)) media += FileCategory.IMAGES
            if (granted(Manifest.permission.READ_MEDIA_VIDEO)) media += FileCategory.VIDEOS
            if (granted(Manifest.permission.READ_MEDIA_AUDIO)) media += FileCategory.AUDIO
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                FileCategory.IMAGES !in media && FileCategory.VIDEOS !in media &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            ) {
                // Partial selection: MediaStore returns only the user-picked photos/videos.
                media += FileCategory.IMAGES
                media += FileCategory.VIDEOS
                partial = true
            }
        } else if (readExternal) {
            media += WalkerSelector.MEDIA_CATEGORIES
        }
        return AccessContext(
            sdkInt = sdk,
            tier = executor.tier,
            privilegedAvailable = privileged,
            allFilesAccess = allFiles,
            legacyStorageReadable = legacy,
            mediaGranted = media,
            partialVisualAccess = partial,
        )
    }

    /** Whether the app itself can list [path] (used for [LocationClass.OTHER] roots). */
    suspend fun readableInProcess(path: String): Boolean = withContext(io) {
        try {
            val file = File(path)
            file.exists() && file.canRead()
        } catch (error: SecurityException) {
            false
        }
    }

    /** True when a persisted read grant covers [location] (a tree or document URI). */
    fun hasSafReadGrant(location: String): Boolean {
        val uri = Uri.parse(location)
        val treeRoot: Uri? = try {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
            } else {
                null
            }
        } catch (error: IllegalArgumentException) {
            null
        }
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && (permission.uri == uri || (treeRoot != null && permission.uri == treeRoot))
        }
    }

    /**
     * Opens this app's "All files access" page (API 30+). Callers must add FLAG_ACTIVITY_NEW_TASK
     * when starting it from a non-activity context.
     */
    fun allFilesAccessIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
