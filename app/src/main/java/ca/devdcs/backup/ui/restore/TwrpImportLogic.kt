package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.discovery.partition.PartitionClassifier
import ca.devdcs.backup.core.discovery.partition.PartitionRisk
import ca.devdcs.backup.core.restore.twrp.TwrpCheckStatus
import ca.devdcs.backup.core.restore.twrp.TwrpFileCheck
import ca.devdcs.backup.core.restore.twrp.TwrpPartitionBackup
import ca.devdcs.backup.core.restore.twrp.TwrpPayloadFormat
import ca.devdcs.backup.core.restore.twrp.TwrpPayloadKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the user does with one partition of a TWRP backup. */
@Serializable
enum class TwrpAction { RESTORE_IMAGE, EXTRACT_FILES }

/**
 * The in-progress choice for one TWRP partition: write its raw image (with the same slot choice
 * and overrides as a DFTB partition restore) or extract its filesystem archive into a folder of a
 * destination. Serializable so it survives process death in SavedStateHandle.
 */
@Serializable
data class TwrpActionDraft(
    val folderPath: String,
    val backupName: String,
    val action: TwrpAction,
    val overrides: PartitionOverrides = PartitionOverrides(),
    /** Blank = the partition the image normally restores to. */
    val targetPartition: String = "",
    val extractDestinationId: String? = null,
    val extractFolder: String = "",
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun encode(draft: TwrpActionDraft): String = json.encodeToString(serializer(), draft)

        fun decode(text: String?): TwrpActionDraft? = if (text.isNullOrBlank()) {
            null
        } else {
            try {
                json.decodeFromString(serializer(), text)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/** Why a TWRP action cannot run as configured. */
enum class TwrpDraftProblem {
    NOT_RAW_IMAGE,
    SPLIT_IMAGE,
    NOT_ARCHIVE,
    ENCRYPTED,
    UNKNOWN_FORMAT,
    INVALID_PARTITION_NAME,
    TARGET_DESTINATION_REQUIRED,
    FOLDER_TRAVERSAL,
}

/** Counts of one sidecar verification. */
data class TwrpVerifySummary(val total: Int, val ok: Int, val mismatched: Int, val missingSidecar: Int, val unreadable: Int) {
    val allOk: Boolean get() = total > 0 && ok == total
    val damaged: Boolean get() = mismatched > 0
}

/** Pure rules of the TWRP import screen. */
object TwrpImportRules {

    private val PARTITION_NAME = Regex("[A-Za-z0-9_.-]{1,64}")

    /** Partition names a typed target may never be (they are refused by the writer anyway). */
    fun isValidPartitionName(name: String): Boolean = PARTITION_NAME.matches(name) && name != "." && name != ".."

    fun targetPartition(draft: TwrpActionDraft, partition: TwrpPartitionBackup): String =
        draft.targetPartition.trim().ifEmpty { partition.partitionName }

    /** True when the image (or the typed target) is a device-unique identity partition. */
    fun isCriticalIdentity(partitionName: String): Boolean {
        val category = PartitionClassifier.category(partitionName)
        return PartitionClassifier.risk(partitionName, category, null) == PartitionRisk.CRITICAL_IDENTITY
    }

    fun defaultExtractFolder(backupName: String, partition: TwrpPartitionBackup): String =
        listOf(EXTRACT_ROOT, safeSegment(backupName), safeSegment(partition.backupName)).joinToString("/")

    fun problems(draft: TwrpActionDraft, partition: TwrpPartitionBackup, format: TwrpPayloadFormat?): List<TwrpDraftProblem> {
        val problems = ArrayList<TwrpDraftProblem>()
        if (format == TwrpPayloadFormat.ENCRYPTED) problems += TwrpDraftProblem.ENCRYPTED
        when (draft.action) {
            TwrpAction.RESTORE_IMAGE -> {
                if (partition.kind != TwrpPayloadKind.RAW_IMAGE) problems += TwrpDraftProblem.NOT_RAW_IMAGE
                if (partition.isSplit || partition.parts.size != 1) problems += TwrpDraftProblem.SPLIT_IMAGE
                val typed = draft.targetPartition.trim()
                if (typed.isNotEmpty() && !isValidPartitionName(typed)) problems += TwrpDraftProblem.INVALID_PARTITION_NAME
            }
            TwrpAction.EXTRACT_FILES -> {
                if (partition.kind != TwrpPayloadKind.FILESYSTEM_ARCHIVE) problems += TwrpDraftProblem.NOT_ARCHIVE
                if (format == TwrpPayloadFormat.UNKNOWN || format == TwrpPayloadFormat.EMPTY) problems += TwrpDraftProblem.UNKNOWN_FORMAT
                if (draft.extractDestinationId.isNullOrBlank()) problems += TwrpDraftProblem.TARGET_DESTINATION_REQUIRED
                if (draft.extractFolder.split('/').any { it == ".." || it == "." }) problems += TwrpDraftProblem.FOLDER_TRAVERSAL
            }
        }
        return problems
    }

    /** Destination-relative extract folder without leading/trailing or doubled slashes. */
    fun normalizeFolder(raw: String): String = raw.trim().split('/').filter { it.isNotEmpty() }.joinToString("/")

    /** Which actions a partition offers at all (before format sniffing). */
    fun actionsFor(partition: TwrpPartitionBackup): List<TwrpAction> = when (partition.kind) {
        TwrpPayloadKind.RAW_IMAGE -> listOf(TwrpAction.RESTORE_IMAGE)
        TwrpPayloadKind.FILESYSTEM_ARCHIVE -> listOf(TwrpAction.EXTRACT_FILES)
    }

    fun summarize(checks: List<TwrpFileCheck>): TwrpVerifySummary = TwrpVerifySummary(
        total = checks.size,
        ok = checks.count { it.status == TwrpCheckStatus.OK },
        mismatched = checks.count { it.status == TwrpCheckStatus.MISMATCH },
        missingSidecar = checks.count { it.status == TwrpCheckStatus.NO_SIDECAR },
        unreadable = checks.count { it.status == TwrpCheckStatus.SIDECAR_UNREADABLE || it.status == TwrpCheckStatus.READ_FAILED },
    )

    /** Checks of [partition]'s stored files only. */
    fun checksOf(checks: List<TwrpFileCheck>, partition: TwrpPartitionBackup): List<TwrpFileCheck> {
        val paths = partition.parts.map { it.path }.toSet()
        return checks.filter { it.file.path in paths }
    }

    private fun safeSegment(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "_" }
        return if (cleaned == "." || cleaned == "..") "_" else cleaned
    }

    const val EXTRACT_ROOT = "TWRP-extracted"
}
