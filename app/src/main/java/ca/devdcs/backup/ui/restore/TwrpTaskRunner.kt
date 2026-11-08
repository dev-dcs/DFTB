package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.pipeline.engine.EngineClock
import ca.devdcs.backup.core.pipeline.engine.JobServiceLauncher
import ca.devdcs.backup.core.pipeline.engine.ThroughputMeter
import ca.devdcs.backup.core.progress.JobKind
import ca.devdcs.backup.core.progress.JobPhase
import ca.devdcs.backup.core.progress.JobProgress
import ca.devdcs.backup.core.progress.JobProgressBus
import ca.devdcs.backup.core.restore.engine.RestoreProgress
import ca.devdcs.backup.core.restore.partition.PartitionRestoreOptions
import ca.devdcs.backup.core.restore.twrp.TwrpBackup
import ca.devdcs.backup.core.restore.twrp.TwrpExtractResult
import ca.devdcs.backup.core.restore.twrp.TwrpImporter
import ca.devdcs.backup.core.restore.twrp.TwrpPartitionBackup
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.domain.model.Destination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/** Live state of one TWRP image restore or archive extraction. */
data class TwrpTaskState(
    val id: String,
    val action: TwrpAction,
    val destinationId: String,
    val folderPath: String,
    val backupName: String,
    /** Partition written (image) or destination folder written (extract). */
    val target: String,
    val startedAtMillis: Long,
    val processedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val processedItems: Int = 0,
    val currentItem: String? = null,
    val bytesPerSecond: Long = 0L,
    val warnings: List<String> = emptyList(),
    val cancelRequested: Boolean = false,
    val finished: Boolean = false,
    val ok: Boolean? = null,
    val error: String? = null,
    /** Typed cause of a thrown failure (localized in the UI); [error] stays as raw secondary detail. */
    val errorFailure: OpenFailure? = null,
    val cancelled: Boolean = false,
    val extract: TwrpExtractResult? = null,
) {
    /**
     * Image writes report bytes only while the device is being written (the verification read is
     * silent), so any byte counted on a failed image task means the partition is partially written.
     */
    val doNotReboot: Boolean get() = action == TwrpAction.RESTORE_IMAGE && processedBytes > 0L && finished && ok != true

    /** Bytes are reaching the block device right now. */
    val writingDevice: Boolean get() = action == TwrpAction.RESTORE_IMAGE && !finished && processedBytes > 0L
}

/**
 * Runs TWRP image restores and archive extractions on the application scope (they can take long
 * and a half-written partition must never depend on a screen staying open), publishes their
 * progress on [JobProgressBus] so the foreground service and notifications show them, and stops
 * them through the bus's cancel request at the importer's safe points.
 */
@Singleton
class TwrpTaskRunner @Inject constructor(
    private val importer: TwrpImporter,
    private val progressBus: JobProgressBus,
    private val serviceLauncher: JobServiceLauncher,
    private val clock: EngineClock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _tasks = MutableStateFlow<Map<String, TwrpTaskState>>(emptyMap())
    val tasks: StateFlow<Map<String, TwrpTaskState>> = _tasks.asStateFlow()

    fun restoreImage(
        destination: Destination,
        backup: TwrpBackup,
        partition: TwrpPartitionBackup,
        options: PartitionRestoreOptions,
        targetPartition: String,
        title: String,
    ): String = launch(destination, backup, partition, TwrpAction.RESTORE_IMAGE, targetPartition, partition.totalBytes, title) { progress ->
        val result = importer.restoreImage(destination, backup, partition, options, progress, targetPartition)
        Outcome(result.ok, result.error, result.warnings, null)
    }

    fun extract(
        source: Destination,
        backup: TwrpBackup,
        partition: TwrpPartitionBackup,
        target: Destination,
        folder: String,
        title: String,
    ): String = launch(source, backup, partition, TwrpAction.EXTRACT_FILES, folder, null, title) { progress ->
        val result = importer.extract(source, backup, partition, target, folder, progress)
        Outcome(result.ok, result.error, result.warnings, result)
    }

    fun cancel(id: String) {
        val task = _tasks.value[id] ?: return
        if (task.finished) return
        mutate(id) { it.copy(cancelRequested = true) }
        progressBus.requestCancel(id)
    }

    fun dismiss(id: String) {
        _tasks.update { map -> if (map[id]?.finished == true) map - id else map }
    }

    fun latestFor(destinationId: String, folderPath: String): TwrpTaskState? =
        _tasks.value.values.filter { it.destinationId == destinationId && it.folderPath == folderPath }.maxByOrNull { it.startedAtMillis }

    private class Outcome(val ok: Boolean, val error: String?, val warnings: List<String>, val extract: TwrpExtractResult?, val failure: OpenFailure? = null)

    private fun launch(
        destination: Destination,
        backup: TwrpBackup,
        partition: TwrpPartitionBackup,
        action: TwrpAction,
        target: String,
        totalBytes: Long?,
        title: String,
        block: suspend (RestoreProgress) -> Outcome,
    ): String {
        val id = UUID.randomUUID().toString()
        val startedAt = clock.nowMillis()
        val bytes = AtomicLong(0L)
        val items = AtomicInteger(0)
        val warnings: MutableList<String> = Collections.synchronizedList(ArrayList())
        val current = AtomicReference<String?>(null)
        val meter = ThroughputMeter()
        val initial = TwrpTaskState(
            id = id,
            action = action,
            destinationId = destination.id,
            folderPath = backup.folderPath,
            backupName = partition.backupName,
            target = target,
            startedAtMillis = startedAt,
            totalBytes = totalBytes,
        )
        _tasks.update { it + (id to initial) }

        fun snapshot(phase: JobPhase, message: String?): JobProgress {
            val now = clock.nowMillis()
            val processed = bytes.get()
            val rate = synchronized(meter) { meter.sample(now, processed) }
            mutate(id) {
                it.copy(
                    processedBytes = processed,
                    processedItems = items.get(),
                    currentItem = current.get(),
                    bytesPerSecond = rate,
                    warnings = synchronized(warnings) { warnings.toList() },
                )
            }
            return JobProgress(
                jobId = id,
                kind = JobKind.RESTORE,
                title = title,
                phase = phase,
                currentItem = current.get(),
                processedBytes = processed,
                totalBytes = totalBytes ?: 0L,
                processedItems = items.get(),
                bytesPerSecond = rate,
                startedAtMillis = startedAt,
                updatedAtMillis = now,
                message = message,
            )
        }

        val progress = object : RestoreProgress {
            override fun onBytes(delta: Long) {
                bytes.addAndGet(delta)
            }

            override fun onItem(name: String) {
                items.incrementAndGet()
                current.set(name)
            }

            override fun onWarning(message: String) {
                warnings += message
            }

            override fun isCancelled(): Boolean = progressBus.isCancelRequested(id)
        }

        scope.launch {
            serviceLauncher.ensureStarted()
            progressBus.publish(snapshot(JobPhase.RUNNING, null))
            val ticker = launch {
                while (isActive) {
                    delay(TICK_MILLIS)
                    progressBus.publish(snapshot(JobPhase.RUNNING, null))
                }
            }
            var outcome: Outcome? = null
            var cancelled = false
            try {
                outcome = block(progress)
            } catch (e: CancellationException) {
                cancelled = true
                if (!progressBus.isCancelRequested(id)) throw e
            } catch (e: Exception) {
                DftbLog.e(TAG, "TWRP task $id failed", e)
                outcome = Outcome(false, e.message ?: e.javaClass.simpleName, emptyList(), null, BackupUnlocker.failureOf(e))
            } finally {
                ticker.cancel()
                val result = outcome
                val phase = when {
                    cancelled -> JobPhase.CANCELLED
                    result?.ok == true -> JobPhase.COMPLETED
                    else -> JobPhase.FAILED
                }
                val terminal = snapshot(phase, result?.error)
                progressBus.publish(terminal)
                result?.warnings?.forEach { if (it !in warnings) warnings += it }
                mutate(id) {
                    it.copy(
                        finished = true,
                        ok = if (cancelled) false else result?.ok,
                        error = result?.error,
                        errorFailure = result?.failure,
                        cancelled = cancelled,
                        extract = result?.extract,
                        warnings = synchronized(warnings) { warnings.toList() },
                    )
                }
            }
        }
        return id
    }

    private fun mutate(id: String, transform: (TwrpTaskState) -> TwrpTaskState) {
        _tasks.update { map -> map[id]?.let { map + (id to transform(it)) } ?: map }
    }

    private companion object {
        const val TAG = "TwrpTasks"
        const val TICK_MILLIS = 250L
    }
}
