package ca.devdcs.backup.core.pipeline.engine

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.FilenameMasker
import java.io.File
import java.time.ZoneId

/**
 * Android-facing seams of the backup engine. They exist so [BackupEngine] is pure JVM code that
 * unit tests drive with fakes, while the real implementations (AndroidBackupPlatform.kt) touch
 * `Build`, the app's private storage, the foreground service and localized resources.
 */

/** Device + app identity recorded in the manifest so a restore can refuse a foreign image. */
interface DeviceInfoProvider {
    suspend fun device(): ManifestDevice
    fun app(): ManifestApp
}

/** App-private scratch space for LOCAL_STAGING artifacts. */
interface StagingArea {
    /** A fresh, existing directory for [jobId] (created on demand). */
    fun dirFor(jobId: String): File

    /** Usable bytes on the staging filesystem, or null when unknown. */
    fun freeBytes(): Long?

    /** Delete everything staged for [jobId]; returns false when something could not be removed. */
    fun release(jobId: String): Boolean
}

/** Starts the foreground service that keeps the process alive and mirrors the progress bus. */
fun interface JobServiceLauncher {
    fun ensureStarted()
}

/** Wall clock + zone, injectable so naming and durations are deterministic in tests. */
interface EngineClock {
    fun nowMillis(): Long
    fun zone(): ZoneId
}

/** Localized, user-facing text for progress messages, History and the result notification. */
interface BackupMessages {
    fun planning(): String
    fun preflight(): String
    fun uploading(name: String): String
    fun verifying(name: String): String
    fun writingManifest(): String
    fun completed(artifactCount: Int): String
    fun completedWithWarnings(artifactCount: Int, warningCount: Int): String
    fun cancelled(): String
    fun failure(failure: BackupFailure): String
    fun warning(warning: BackupWarning): String
}

/**
 * A keyed filename masker for one job plus the public parameters (scheme, salt, KDF cost) that a
 * restore needs to rebuild the same key. Close it to wipe the derived keys.
 */
class JobMasker(
    val masker: FilenameMasker,
    val params: Map<String, String>,
    private val onClose: () -> Unit,
) : AutoCloseable {
    override fun close() = onClose()
}

/**
 * A job-scoped encryption key: derived once (one Argon2 run for passphrases), then used for every
 * stream and retry of the job. Close it at job end to wipe the master.
 */
interface JobStreamKey : AutoCloseable {
    /** Exact header parameters every stream of this job records (no key material). */
    fun describe(): ca.devdcs.backup.core.pipeline.EncryptionHeader

    /** New encrypting stream; abort (never close) it on failure so a partial cannot validate. */
    suspend fun encrypt(out: java.io.OutputStream): java.io.OutputStream
}

/** Derives [JobStreamKey]s; exists so the engine stays testable without the real cipher. */
interface JobKeyPreparer {
    suspend fun prepare(key: CipherKeySpec): JobStreamKey

    /** Authenticated decryption (reads earlier runs' encrypted manifests on resume). */
    suspend fun decrypt(input: java.io.InputStream, key: CipherKeySpec): java.io.InputStream
}

/** Creates a fresh [JobMasker] (new random salt) for each job from the job's key. */
interface MaskerProvider {
    /**
     * [params] (a previous run's [JobMasker.params]) re-creates the very same masker so a resumed
     * job keeps its names; null or unparseable params start a new set with a fresh salt.
     */
    suspend fun open(key: CipherKeySpec, params: Map<String, String>? = null): JobMasker
}
