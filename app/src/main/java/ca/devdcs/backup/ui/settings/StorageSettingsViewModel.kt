package ca.devdcs.backup.ui.settings

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.BuildConfig
import ca.devdcs.backup.R
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.logging.LogBuffer
import ca.devdcs.backup.core.pipeline.archive.DefaultArchiveFactory
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.time.ZoneId
import javax.inject.Inject

/** Measured sizes; null while (re)measuring. */
data class CacheUsage(val stagingBytes: Long, val otherCacheBytes: Long) {
    val totalBytes: Long get() = stagingBytes + otherCacheBytes
}

data class StorageSettingsUiState(
    val usage: CacheUsage? = null,
    /** True when the last measurement failed; [usage] is then null and the UI offers a retry. */
    val measureFailed: Boolean = false,
    val clearing: Boolean = false,
    val jobsActive: Boolean = false,
    val logLineCount: Int = 0,
    val exporting: Boolean = false,
)

/** One-shot effects of the storage page. */
sealed interface StorageEvent {
    data class Message(val message: SettingsMessage) : StorageEvent

    /** The log was written to a private file; the screen opens the share sheet for [uri]. */
    data class ShareLog(val uri: Uri) : StorageEvent
}

/**
 * Cache accounting (archive staging plus other temporary files) with a guarded clear, and the
 * diagnostic log export. Clearing is refused while any job runs: the engines keep spool files,
 * FIFOs and 7z staging archives in the cache while working, and deleting them mid-job would fail
 * the job or corrupt an artifact.
 */
@HiltViewModel
class StorageSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val archiveFactory: DefaultArchiveFactory,
    private val logBuffer: LogBuffer,
    private val preferences: AppPreferencesRepository,
    private val jobProgressBus: JobProgressBus,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val _events = Channel<StorageEvent>(Channel.BUFFERED)
    val events: Flow<StorageEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<StorageSettingsUiState> = combine(
        local,
        jobProgressBus.activeJobs.map { jobs -> jobs.values.any { !it.isTerminal } },
        logBuffer.lines.map { it.size },
    ) { state, jobsActive, logLines ->
        StorageSettingsUiState(
            usage = state.usage,
            measureFailed = state.measureFailed,
            clearing = state.clearing,
            jobsActive = jobsActive,
            logLineCount = logLines,
            exporting = state.exporting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StorageSettingsUiState())

    init {
        measure()
    }

    fun measure() {
        viewModelScope.launch {
            local.update { it.copy(usage = null, measureFailed = false) }
            val usage = try {
                withContext(ioDispatcher) { computeUsage() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Cache measurement failed", e)
                null
            }
            local.update { it.copy(usage = usage, measureFailed = usage == null) }
        }
    }

    fun clearStaging() = clear(ClearScope.STAGING)

    fun clearAllCache() = clear(ClearScope.ALL)

    /** Writes the current log to a private cache file and asks the screen to share it. */
    fun shareLog() {
        if (local.value.exporting) return
        local.update { it.copy(exporting = true) }
        viewModelScope.launch {
            try {
                val uri = withContext(ioDispatcher) {
                    val (name, text) = renderLog()
                    val dir = File(context.cacheDir, SHARE_DIR)
                    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
                    // Only the newest export is kept; older shares were already handed off.
                    dir.listFiles()?.forEach { it.delete() }
                    val file = File(dir, name)
                    file.writeText(text, Charsets.UTF_8)
                    FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
                }
                _events.trySend(StorageEvent.ShareLog(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Log share failed", e)
                message(R.string.settings_storage_log_export_failed)
            } finally {
                local.update { it.copy(exporting = false) }
            }
        }
    }

    /** Writes the current log into a document the user created through the SAF picker. */
    fun saveLog(target: Uri) {
        if (local.value.exporting) return
        local.update { it.copy(exporting = true) }
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val (_, text) = renderLog()
                    openForWrite(target).use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                message(R.string.settings_storage_log_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Log save failed", e)
                message(R.string.settings_storage_log_export_failed)
            } finally {
                local.update { it.copy(exporting = false) }
            }
        }
    }

    /** Suggested name for the save picker, in the device's zone so it matches the header. */
    fun suggestedLogFileName(): String = LogExportFormatter.fileName(System.currentTimeMillis(), ZoneId.systemDefault())

    private fun clear(scope: ClearScope) {
        if (local.value.clearing) return
        if (jobProgressBus.activeJobs.value.values.any { !it.isTerminal }) {
            message(R.string.settings_storage_clear_blocked)
            return
        }
        local.update { it.copy(clearing = true) }
        viewModelScope.launch {
            try {
                val outcome = withContext(ioDispatcher) {
                    when (scope) {
                        ClearScope.STAGING -> CacheFiles.clearContents(archiveFactory.stagingDirectory())
                        ClearScope.ALL -> cacheRoots()
                            .map { CacheFiles.clearContents(it) }
                            .fold(ClearOutcome(0L, 0)) { acc, next ->
                                ClearOutcome(acc.freedBytes + next.freedBytes, acc.failedEntries + next.failedEntries)
                            }
                    }
                }
                DftbLog.i(TAG, "Cleared $scope cache: ${outcome.freedBytes} bytes, ${outcome.failedEntries} entries kept")
                _events.trySend(
                    StorageEvent.Message(
                        if (outcome.failedEntries == 0) {
                            SettingsMessage(R.string.settings_storage_cleared)
                        } else {
                            SettingsMessage.count(R.plurals.settings_storage_cleared_partial, outcome.failedEntries)
                        },
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Cache clear failed", e)
                message(R.string.settings_storage_clear_failed)
            } finally {
                local.update { it.copy(clearing = false) }
                measure()
            }
        }
    }

    private fun computeUsage(): CacheUsage {
        val staging = archiveFactory.stagingDirectory()
        val stagingBytes = CacheFiles.sizeOf(staging)
        val allBytes = cacheRoots().sumOf { CacheFiles.sizeOf(it) }
        // The staging dir lives inside cacheDir, so subtract it from the overall figure.
        return CacheUsage(stagingBytes = stagingBytes, otherCacheBytes = (allBytes - stagingBytes).coerceAtLeast(0L))
    }

    private fun cacheRoots(): List<File> = listOfNotNull(context.cacheDir, context.externalCacheDir).distinct()

    private suspend fun renderLog(): Pair<String, String> {
        val prefs = preferences.preferences.first()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val header = LogExportHeader(
            appName = BuildConfig.APP_DISPLAY_NAME,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            buildType = BuildConfig.BUILD_TYPE,
            packageName = context.packageName,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidRelease = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT,
            operatingMode = prefs.operatingMode?.storageKey,
            exportedAtMillis = now,
        )
        return LogExportFormatter.fileName(now, zone) to LogExportFormatter.format(logBuffer.lines.value, header, zone)
    }

    private fun openForWrite(target: Uri): OutputStream {
        val resolver = context.contentResolver
        return try {
            resolver.openOutputStream(target, "wt")
        } catch (e: FileNotFoundException) {
            resolver.openOutputStream(target, "w")
        } catch (e: IllegalArgumentException) {
            resolver.openOutputStream(target, "w")
        } ?: throw IOException("The document provider returned no output stream")
    }

    private fun message(res: Int) {
        _events.trySend(StorageEvent.Message(SettingsMessage(res)))
    }

    private enum class ClearScope { STAGING, ALL }

    private data class LocalState(
        val usage: CacheUsage? = null,
        val measureFailed: Boolean = false,
        val clearing: Boolean = false,
        val exporting: Boolean = false,
    )

    private companion object {
        const val TAG = "SettingsStorage"
        const val SHARE_DIR = "log_export"
        /** Matches the FileProvider authority declared in the manifest (`${applicationId}.files`). */
        const val FILE_PROVIDER_SUFFIX = ".files"
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
