package ca.devdcs.backup.core.pipeline.engine

import android.content.Context
import android.os.Build
import ca.devdcs.backup.BuildConfig
import ca.devdcs.backup.R
import ca.devdcs.backup.core.locale.LocaleManager
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.EncryptionHeader
import ca.devdcs.backup.core.pipeline.crypto.AesGcmStreamCipher
import ca.devdcs.backup.core.pipeline.crypto.FilenameMaskerFactory
import ca.devdcs.backup.core.pipeline.crypto.MaskerParams
import java.io.InputStream
import java.io.OutputStream
import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.service.DftbForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Reads identity from `Build` and the A/B slot from `getprop` (no privilege needed). */
@Singleton
class AndroidDeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DeviceInfoProvider {

    override suspend fun device(): ManifestDevice = ManifestDevice(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        device = Build.DEVICE.orEmpty(),
        fingerprint = Build.FINGERPRINT.orEmpty(),
        sdkInt = Build.VERSION.SDK_INT,
        slotSuffix = slotSuffix(),
    )

    override fun app(): ManifestApp = ManifestApp(
        packageName = context.packageName,
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
    )

    private suspend fun slotSuffix(): String? = withContext(io) {
        try {
            val process = ProcessBuilder("getprop", SLOT_PROP).redirectErrorStream(true).start()
            try {
                val finished = process.waitFor(GETPROP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!finished) return@withContext null
                val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
                value.takeIf { it == "_a" || it == "_b" }
            } finally {
                process.destroy()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.w(TAG, "Could not read $SLOT_PROP", e)
            null
        }
    }

    private companion object {
        const val TAG = "DeviceInfo"
        const val SLOT_PROP = "ro.boot.slot_suffix"
        const val GETPROP_TIMEOUT_SECONDS = 3L
    }
}

/**
 * Staging under `noBackupFilesDir/backup-staging/<jobId>`: private to the app, excluded from OS
 * backups, and (unlike cacheDir) never purged by the system in the middle of a job.
 */
@Singleton
class AppStagingArea @Inject constructor(
    @ApplicationContext private val context: Context,
) : StagingArea {

    private val root: File get() = File(context.noBackupFilesDir, ROOT_NAME)

    override fun dirFor(jobId: String): File {
        val dir = File(root, ArtifactNaming.sanitizeSegment(jobId))
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw java.io.IOException("Cannot create staging folder ${dir.path}")
        }
        return dir
    }

    /** The smaller of this area and the cache dir, where archive writers spool 7z / unsized streams. */
    override fun freeBytes(): Long? {
        val probe = root.takeIf { it.exists() } ?: context.noBackupFilesDir
        return listOf(probe.usableSpace, context.cacheDir.usableSpace).filter { it > 0L }.minOrNull()
    }

    override fun release(jobId: String): Boolean {
        val dir = File(root, ArtifactNaming.sanitizeSegment(jobId))
        return !dir.exists() || dir.deleteRecursively()
    }

    private companion object {
        const val ROOT_NAME = "backup-staging"
    }
}

/** Starts [DftbForegroundService]; tolerates background-start restrictions (WorkManager path). */
@Singleton
class ForegroundServiceJobLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
) : JobServiceLauncher {
    override fun ensureStarted() {
        try {
            DftbForegroundService.start(context)
        } catch (e: IllegalStateException) {
            // Android 12+ refuses FGS starts from the background; a WorkManager foreground worker
            // keeps the process alive in that case and the bus still carries progress.
            DftbLog.w(TAG, "Foreground service could not be started", e)
        } catch (e: SecurityException) {
            DftbLog.w(TAG, "Foreground service not permitted", e)
        }
    }

    private companion object {
        const val TAG = "BackupEngine"
    }
}

/** Real wall clock in the device's zone (job folder timestamps are local time). */
@Singleton
class SystemEngineClock @Inject constructor() : EngineClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/** Resolves [BackupMessages] in the app's chosen language. */
@Singleton
class AndroidBackupMessages @Inject constructor(
    private val localeManager: LocaleManager,
) : BackupMessages {

    private val res get() = localeManager.localizedContext().resources

    private fun str(id: Int, vararg args: Any): String = if (args.isEmpty()) res.getString(id) else res.getString(id, *args)

    override fun planning(): String = str(R.string.backup_engine_planning)
    override fun preflight(): String = str(R.string.backup_engine_preflight)
    override fun uploading(name: String): String = str(R.string.backup_engine_uploading, name)
    override fun verifying(name: String): String = str(R.string.backup_engine_verifying, name)
    override fun writingManifest(): String = str(R.string.backup_engine_writing_manifest)
    override fun completed(artifactCount: Int): String =
        res.getQuantityString(R.plurals.backup_engine_completed, artifactCount, artifactCount)
    override fun completedWithWarnings(artifactCount: Int, warningCount: Int): String =
        res.getQuantityString(R.plurals.backup_engine_completed, artifactCount, artifactCount) + " " +
            res.getQuantityString(R.plurals.backup_engine_warning_count, warningCount, warningCount)
    override fun cancelled(): String = str(R.string.backup_engine_cancelled)

    override fun failure(failure: BackupFailure): String {
        val base = when (failure.code) {
            BackupErrorCode.NO_DESTINATION -> str(R.string.backup_engine_error_no_destination)
            BackupErrorCode.DESTINATION_NOT_FOUND -> str(R.string.backup_engine_error_destination_not_found)
            BackupErrorCode.NOTHING_TO_BACK_UP -> str(R.string.backup_engine_error_nothing)
            BackupErrorCode.SOURCES_UNAVAILABLE -> str(R.string.backup_engine_error_sources_unavailable)
            BackupErrorCode.TWRP_ENCRYPTION_CONFLICT -> str(R.string.backup_engine_error_twrp_encryption)
            BackupErrorCode.MASKING_REQUIRES_ENCRYPTION -> str(R.string.backup_engine_error_masking_needs_encryption)
            BackupErrorCode.RESUME_FOLDER_INVALID -> str(R.string.backup_engine_error_resume_folder)
            BackupErrorCode.INSUFFICIENT_DESTINATION_SPACE -> str(R.string.backup_engine_error_destination_space)
            BackupErrorCode.INSUFFICIENT_STAGING_SPACE -> str(R.string.backup_engine_error_staging_space)
            BackupErrorCode.ENCRYPTION_UNSUPPORTED -> str(R.string.backup_engine_error_encryption_unsupported)
            BackupErrorCode.ENCRYPTION_KEY_MISSING -> str(R.string.backup_engine_error_key_missing)
            BackupErrorCode.TRANSFER_MODE_UNSUPPORTED -> str(R.string.backup_engine_error_transfer_unsupported)
            BackupErrorCode.SOURCE_FAILED -> str(R.string.backup_engine_error_source)
            BackupErrorCode.SOURCE_SHAPE -> str(R.string.backup_engine_error_source_shape)
            BackupErrorCode.STORAGE -> str(R.string.backup_engine_error_storage)
            BackupErrorCode.VERIFY_MISMATCH -> str(R.string.backup_engine_error_verify)
            BackupErrorCode.MANIFEST_FAILED -> str(R.string.backup_engine_error_manifest)
            BackupErrorCode.ALL_ARTIFACTS_FAILED -> str(R.string.backup_engine_error_all_failed)
            BackupErrorCode.INTERNAL -> str(R.string.backup_engine_error_internal)
        }
        return failure.detail?.let { "$base ($it)" } ?: base
    }

    override fun warning(warning: BackupWarning): String {
        val base = when (warning.code) {
            BackupWarningCode.FREE_SPACE_UNKNOWN -> str(R.string.backup_engine_warning_space_unknown)
            BackupWarningCode.FREE_SPACE_OVERRIDDEN -> str(R.string.backup_engine_warning_space_overridden)
            BackupWarningCode.FORMAT_SUBSTITUTED -> str(R.string.backup_engine_warning_format_substituted)
            BackupWarningCode.SOURCE_UNAVAILABLE -> str(R.string.backup_engine_warning_source_unavailable)
            BackupWarningCode.SOURCE_PARTIAL -> str(R.string.backup_engine_warning_source_partial)
            BackupWarningCode.TWRP_STORED_VERBATIM -> str(R.string.backup_engine_warning_twrp_verbatim)
            BackupWarningCode.TWRP_NOT_ON_DEVICE_STORAGE -> str(R.string.backup_engine_warning_twrp_not_device)
            BackupWarningCode.ESTIMATE_FAILED -> str(R.string.backup_engine_warning_estimate_failed)
            BackupWarningCode.ITEM_SIZE_MISMATCH -> str(R.string.backup_engine_warning_size_mismatch)
            BackupWarningCode.ARTIFACT_FAILED -> str(R.string.backup_engine_warning_artifact_failed)
            BackupWarningCode.HASH_RECORDS_FAILED -> str(R.string.backup_engine_warning_hash_records)
            BackupWarningCode.CHECKPOINT_WRITE_FAILED -> str(R.string.backup_engine_warning_checkpoint)
            BackupWarningCode.CLEANUP_FAILED -> str(R.string.backup_engine_warning_cleanup)
            BackupWarningCode.RESUME_SKIPPED -> str(R.string.backup_engine_warning_resume_skipped)
            BackupWarningCode.RESUME_UNINDEXED -> str(R.string.backup_engine_warning_resume_unindexed)
            BackupWarningCode.PRIOR_MANIFEST_UNREADABLE -> str(R.string.backup_engine_warning_prior_manifest)
        }
        val subject = warning.subject?.let { "$it: " }.orEmpty()
        return subject + base + (warning.detail?.let { " ($it)" }.orEmpty())
    }
}

/** [JobKeyPreparer] over [AesGcmStreamCipher.prepare]: one KDF run per job. */
@Singleton
class AesGcmJobKeyPreparer @Inject constructor(
    private val cipher: AesGcmStreamCipher,
) : JobKeyPreparer {
    override suspend fun prepare(key: CipherKeySpec): JobStreamKey {
        val prepared = cipher.prepare(key)
        return object : JobStreamKey {
            override fun describe(): EncryptionHeader = cipher.describe(prepared)
            override suspend fun encrypt(out: OutputStream): OutputStream = cipher.encrypt(out, prepared)
            override fun close() = prepared.close()
        }
    }

    override suspend fun decrypt(input: InputStream, key: CipherKeySpec): InputStream = cipher.decrypt(input, key)
}

/** [MaskerProvider] backed by the SIV masker; each job gets a new random salt. */
@Singleton
class SivMaskerProvider @Inject constructor(
    private val factory: FilenameMaskerFactory,
) : MaskerProvider {
    override suspend fun open(key: CipherKeySpec, params: Map<String, String>?): JobMasker {
        val params = params?.let(MaskerParams::fromMap) ?: factory.newParams()
        val masker = factory.create(key, params)
        return JobMasker(masker, params.toMap(), masker::close)
    }
}
