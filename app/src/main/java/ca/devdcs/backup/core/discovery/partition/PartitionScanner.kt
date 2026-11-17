package ca.devdcs.backup.core.discovery.partition

import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A scan outcome plus when it was taken, so the UI can show "scanned 2 min ago". */
data class CachedPartitionScan(
    val outcome: PartitionScanOutcome,
    /** Wall-clock time (epoch millis) the scan finished. */
    val scannedAtMillis: Long,
)

/**
 * Discovers the device's block partitions for partition imaging. Needs the ROOT tier: on any other
 * tier it returns [PartitionScanUnavailable] with [PartitionUnavailableReason.ROOT_REQUIRED] rather
 * than failing. Runs one batched, strictly read-only root script ([PartitionScanScript]) and parses
 * it with the pure [PartitionAssembler].
 *
 * The layout cannot change without a reboot/OTA, so a successful result is cached in memory;
 * [rescan] forces a fresh read. Unavailable outcomes are never served from cache (the user may
 * grant root a second later), but they are recorded in [lastScan] for diagnostics.
 */
@Singleton
class PartitionScanner @Inject constructor(
    private val router: CommandExecutorRouter,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val mutex = Mutex()

    @Volatile
    private var cache: CachedPartitionScan? = null

    /** The most recent scan (successful or not), or null if none ran in this process. */
    val lastScan: CachedPartitionScan? get() = cache

    /** The cached successful scan if present, otherwise a fresh one. */
    suspend fun scan(): PartitionScanOutcome {
        cache?.let { if (it.outcome is PartitionScanResult) return it.outcome }
        return rescan()
    }

    /** Always re-reads the device and replaces the cache. */
    suspend fun rescan(): PartitionScanOutcome = mutex.withLock {
        val outcome = runScan()
        cache = CachedPartitionScan(outcome, System.currentTimeMillis())
        outcome
    }

    /** Drop the cached result (e.g. after an OTA flips the slot). */
    fun invalidate() {
        cache = null
    }

    private suspend fun runScan(): PartitionScanOutcome = withContext(ioDispatcher) {
        try {
            val executor = router.current()
            if (executor.tier != ElevationTier.ROOT) {
                return@withContext PartitionScanUnavailable(PartitionUnavailableReason.ROOT_REQUIRED, "tier=${executor.tier}")
            }
            if (!executor.isAvailable()) {
                return@withContext PartitionScanUnavailable(PartitionUnavailableReason.ROOT_NOT_GRANTED)
            }
            val result = executor.execute(Command(argv = listOf("sh", "-c", PartitionScanScript.build()), timeoutMillis = SCAN_TIMEOUT_MILLIS))
            if (!result.launched) {
                return@withContext PartitionScanUnavailable(PartitionUnavailableReason.SCAN_FAILED, result.stderrText.take(MAX_DETAIL))
            }
            val sections = PartitionScanScript.parseSections(result.stdout)
            val outcome = PartitionAssembler.assemble(sections, result.stderr, result.exitCode)
            if (outcome is PartitionScanResult) {
                DftbLog.i(TAG, "Scanned ${outcome.partitions.size} partitions (ab=${outcome.abDevice}, slot=${outcome.activeSlot}, warnings=${outcome.warnings.size})")
            } else {
                DftbLog.w(TAG, "Partition scan found nothing: $outcome")
            }
            outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DftbLog.e(TAG, "Partition scan failed", e)
            PartitionScanUnavailable(PartitionUnavailableReason.SCAN_FAILED, e.message?.take(MAX_DETAIL))
        }
    }

    private companion object {
        const val TAG = "PartitionScanner"
        const val SCAN_TIMEOUT_MILLIS = 30_000L
        const val MAX_DETAIL = 2000
    }
}
