package ca.devdcs.backup.companion

import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.root.RootScriptBuilder
import ca.devdcs.backup.core.exec.root.RootShellManager
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import ca.devdcs.backup.di.ApplicationScope
import ca.devdcs.backup.domain.model.JobCheckpoint
import ca.devdcs.backup.domain.repository.CheckpointRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the resumable-job journal from Room into the companion module's root-owned journal
 * directory ([ModuleManifest.JOURNAL_DIR]).
 *
 * It exists because the module's late-boot `service.sh` cannot read the app's Room database (no
 * `sqlite3` on stock devices, and the DB is credential-encrypted until unlock), yet it must decide at
 * boot whether interrupted jobs are waiting so it can wake the app early. Room stays the authoritative
 * journal; this class keeps a tiny JSON projection of every unfinished, resume-after-reboot job in
 * `<journal>/<jobId>.json` (with exactly the `"resumeAfterReboot":true` and `"status":"…"` markers the
 * script greps for) and removes files for jobs that finished or were deleted.
 *
 * Active only in [OperatingMode.ROOT_MODULE]; writes go through [RootShellManager] in one shell
 * command per change, each file written to a temp name and renamed so the script never reads a
 * half-written file. Syncs only when the projection (id, status, resume flag) changes, not on every
 * progress update, so a running job does not spawn a root command per checkpoint.
 */
@Singleton
class CompanionJournalMirror @Inject constructor(
    private val checkpointRepository: CheckpointRepository,
    private val preferencesRepository: AppPreferencesRepository,
    private val rootShellManager: RootShellManager,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {

    private val started = AtomicBoolean(false)

    /** Begin mirroring for the lifetime of the process. Idempotent; called from Application.onCreate. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            val modeFlow = preferencesRepository.preferences.map { it.operatingMode }.distinctUntilChanged()
            val journalFlow = checkpointRepository.observeAll()
                .map { checkpoints -> checkpoints.filter(::belongsInJournal).map(::toEntry).sortedBy { it.fileStem } }
                .distinctUntilChanged()
            combine(modeFlow, journalFlow) { mode, entries -> mode to entries }
                .collectLatest { (mode, entries) ->
                    if (mode != OperatingMode.ROOT_MODULE) return@collectLatest
                    // Collapse a burst of status changes (e.g. several jobs finishing) into one write.
                    delay(SETTLE_MILLIS)
                    sync(entries)
                }
        }
    }

    private suspend fun sync(entries: List<JournalEntry>) {
        try {
            val result = rootShellManager.exec(
                Command(argv = listOf("sh", "-c", buildSyncScript(entries)), timeoutMillis = SYNC_TIMEOUT_MILLIS),
            )
            if (result.isSuccess) {
                DftbLog.d(TAG, "Companion journal synced (${entries.size} resumable job(s))")
            } else {
                DftbLog.w(TAG, "Companion journal sync failed (exit=${result.exitCode}): ${result.stderr.joinToString(" ")}")
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            DftbLog.e(TAG, "Companion journal sync failed", error)
        }
    }

    /** The minimal per-job projection the boot script needs; also the change-detection key. */
    internal data class JournalEntry(val fileStem: String, val json: String)

    private fun belongsInJournal(checkpoint: JobCheckpoint): Boolean =
        checkpoint.isResumable && checkpoint.resumeAfterReboot

    private fun toEntry(checkpoint: JobCheckpoint): JournalEntry {
        // Compact encoding yields `"status":"running"` with no spaces, matching service.sh's grep.
        val json = buildJsonObject {
            put("jobId", JsonPrimitive(checkpoint.jobId))
            checkpoint.profileId?.let { put("profileId", JsonPrimitive(it)) }
            put("type", JsonPrimitive(checkpoint.type.storageKey))
            put("status", JsonPrimitive(checkpoint.status.storageKey))
            put("resumeAfterReboot", JsonPrimitive(true))
            put("attempt", JsonPrimitive(checkpoint.attempt))
        }.toString()
        return JournalEntry(fileStem = safeFileStem(checkpoint.jobId), json = json)
    }

    companion object {
        private const val TAG = "JournalMirror"
        private const val SETTLE_MILLIS = 750L
        private const val SYNC_TIMEOUT_MILLIS = 15_000L

        /**
         * Reduce a job id to a filename-safe stem (`[A-Za-z0-9._-]`), so it can never escape the
         * journal directory or act as a glob/`case` metacharacter in the sync script.
         */
        internal fun safeFileStem(jobId: String): String =
            jobId.map { ch -> if (ch.isLetterOrDigit() && ch.code < 128 || ch == '-' || ch == '_' || ch == '.') ch else '_' }
                .joinToString("")
                .trimStart('.')
                .ifEmpty { "job" }

        /**
         * One POSIX `sh` script that (1) creates the 0700 journal dir, (2) writes every entry via a
         * temp file + `mv` with mode 0600, and (3) deletes any `*.json` whose stem is not in [entries].
         * Pure so it can be unit-tested on the JVM.
         */
        internal fun buildSyncScript(entries: List<JournalEntry>): String = buildSyncScript(
            ModuleManifest.JOURNAL_DIR,
            entries.map { it.fileStem to it.json },
        )

        internal fun buildSyncScript(journalDir: String, entries: List<Pair<String, String>>): String {
            val dir = RootScriptBuilder.shellQuote(journalDir)
            val script = StringBuilder()
            script.append("set -e; umask 077; mkdir -p ").append(dir).append("; chmod 0700 ").append(dir).append("; ")
            entries.forEach { (stem, json) ->
                val tmp = RootScriptBuilder.shellQuote("$journalDir/.$stem.json.tmp")
                val target = RootScriptBuilder.shellQuote("$journalDir/$stem.json")
                script.append("printf '%s\\n' ").append(RootScriptBuilder.shellQuote(json)).append(" > ").append(tmp)
                    .append("; chmod 0600 ").append(tmp).append("; mv -f ").append(tmp).append(' ').append(target).append("; ")
            }
            script.append("set +e; for f in ").append(dir).append("/*.json; do [ -f \"\$f\" ] || continue; ")
            script.append("n=\${f##*/}; case \"\${n%.json}\" in ")
            if (entries.isNotEmpty()) {
                script.append(entries.joinToString("|") { it.first }).append(") ;; ")
            }
            script.append("*) rm -f \"\$f\" ;; esac; done; exit 0")
            return script.toString()
        }
    }
}
