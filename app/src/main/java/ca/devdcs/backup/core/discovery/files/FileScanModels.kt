package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.FileFilter

/**
 * Which enumeration strategy produced (or would produce) a root's entries. Recorded per root so the
 * UI can explain why a scan saw what it saw (e.g. "MediaStore only: documents need All files access").
 */
enum class WalkerKind {
    /** In-process java.nio walk; app dirs, or shared storage with All files access / legacy storage. */
    NIO,

    /** Storage Access Framework tree (`content://` URI the user granted). */
    SAF,

    /** One streamed `find` through the Shizuku or Root [ca.devdcs.backup.core.exec.CommandExecutor]. */
    PRIVILEGED_FIND,

    /** MediaStore index query; Standard-tier fallback for media categories without All files access. */
    MEDIA_STORE,
}

/**
 * One file that passed the filter. [relativePath] is '/'-separated and relative to [rootLocation]
 * (it is what the filter's regexes saw); [location] is the absolute path or `content://` URI the
 * pipeline opens to read the bytes.
 */
data class ScannedFile(
    val rootLocation: String,
    val relativePath: String,
    val location: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
    val walker: WalkerKind,
) {
    val extension: String get() = FileFilter.extensionOf(relativePath)
    val category: FileCategory? get() = FileCategory.classify(extension)
}

/** Live counters for a running scan; throttled by [ScanOptions.progressIntervalMillis]. */
data class ScanProgress(
    val dirsVisited: Long,
    val filesMatched: Long,
    val bytesMatched: Long,
    val currentPath: String?,
)

/** Count and total size of matched files sharing one extension ("" = no extension). */
data class ExtensionStat(val extension: String, val count: Long, val bytes: Long)

/** Count and total size of matched files in one [FileCategory] (or unclassified). */
data class CategoryStat(val count: Long, val bytes: Long)

/**
 * The aggregate answer a preview screen needs without holding every file in memory. Top-N lists
 * are bounded by [ScanOptions.topN] regardless of how many files matched.
 */
data class ScanSummary(
    val matchedCount: Long,
    val matchedBytes: Long,
    val dirsVisited: Long,
    /** Directories/files the walker could not read (permission denied, vanished mid-scan, I/O error). */
    val skippedUnreadable: Long,
    /** Most frequent extensions, by count then bytes. */
    val byExtension: List<ExtensionStat>,
    val byCategory: Map<FileCategory, CategoryStat>,
    /** Matched files whose extension belongs to no [FileCategory]. */
    val unclassified: CategoryStat,
    /** Largest matched files, biggest first. */
    val largestFiles: List<ScannedFile>,
    val oldest: ScannedFile?,
    val newest: ScannedFile?,
) {
    companion object {
        val EMPTY = ScanSummary(0, 0, 0, 0, emptyList(), emptyMap(), CategoryStat(0, 0), emptyList(), null, null)
    }
}

/** Tunables for one scan. Preview screens keep [collectFiles] false; the pipeline sets it true. */
data class ScanOptions(
    /** Keep every matched [ScannedFile] in [ScanResult.files] (bounded by [maxCollectedFiles]). */
    val collectFiles: Boolean = false,
    /** Hard cap on [ScanResult.files]; beyond it the summary keeps counting and [ScanResult.truncated] is set. */
    val maxCollectedFiles: Int = DEFAULT_MAX_COLLECTED,
    val topN: Int = DEFAULT_TOP_N,
    /** Skip `.thumbnails`, `.trash*`, cache dirs and `Android/data` (unless the root is inside it). */
    val useDefaultExclusions: Boolean = true,
    val progressIntervalMillis: Long = DEFAULT_PROGRESS_INTERVAL_MS,
) {
    init {
        require(maxCollectedFiles >= 0) { "maxCollectedFiles must be >= 0" }
        require(topN >= 0) { "topN must be >= 0" }
    }

    companion object {
        const val DEFAULT_MAX_COLLECTED = 500_000
        const val DEFAULT_TOP_N = 20
        const val DEFAULT_PROGRESS_INTERVAL_MS = 200L
    }
}

/** Something the user must grant or switch for a blocked location to become scannable. */
sealed interface AccessRequirement {
    /**
     * "All files access" (API 30+). Launch an Intent with [settingsAction] and data
     * `package:<applicationId>`; see [StorageAccessInspector.allFilesAccessIntent].
     */
    data class AllFilesAccess(
        val settingsAction: String = android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
    ) : AccessRequirement

    /** Runtime permissions to request (any one of them unlocks the corresponding media). */
    data class RuntimePermission(val permissions: List<String>) : AccessRequirement

    /** Switch the operating mode to at least [minimum] (Shizuku or Root) and keep it available. */
    data class ElevatedTier(val minimum: ElevationTier) : AccessRequirement

    /** Re-pick the folder through the system picker so a persisted SAF grant exists again. */
    data object SafRegrant : AccessRequirement
}

/** Why a location cannot be scanned in the current tier; mapped to text by [FileScanText]. */
enum class BlockReason {
    NEEDS_ALL_FILES_ACCESS,
    NEEDS_MEDIA_PERMISSION,
    NEEDS_ALL_FILES_ACCESS_FOR_NON_MEDIA,
    NEEDS_ELEVATION_FOR_APP_SANDBOX,
    NEEDS_ROOT,
    NOT_READABLE,
    SAF_PERMISSION_MISSING,
}

/** Problems detected at the root itself (as opposed to one unreadable entry below it). */
enum class RootProblem { NOT_FOUND, NOT_READABLE, NOT_A_DIRECTORY, TOOL_FAILED }

/** Non-fatal conditions worth surfacing next to the results. */
enum class ScanWarning {
    /** Android 14+ partial photo/video selection: MediaStore only returns what the user picked. */
    PARTIAL_MEDIA_ACCESS,

    /** `find` exited with an error other than "some entries were unreadable". */
    FIND_EXIT_NONZERO,

    /** Lines the find parser could not understand (e.g. file names containing a newline). */
    UNPARSEABLE_OUTPUT,

    /** Neither `find -printf` nor `find -exec stat` works in the privileged shell. */
    FIND_UNSUPPORTED,
}

/** What happened to one scanned root. */
data class RootOutcome(
    val location: String,
    /** Null when the root was blocked before any walk. */
    val walker: WalkerKind?,
    val blocked: BlockReason? = null,
    val requirements: List<AccessRequirement> = emptyList(),
    val rootProblem: RootProblem? = null,
    /** Categories this root was meant to cover but could not in the current tier (category scans). */
    val uncoveredCategories: Set<FileCategory> = emptySet(),
    val warnings: Set<ScanWarning> = emptySet(),
) {
    val isUsable: Boolean get() = walker != null && rootProblem == null
}

/** Final result of a scan. */
data class ScanResult(
    val summary: ScanSummary,
    /** Matched files when [ScanOptions.collectFiles]; empty otherwise. */
    val files: List<ScannedFile>,
    /** True when more files matched than [ScanOptions.maxCollectedFiles] allowed to keep. */
    val truncated: Boolean,
    val roots: List<RootOutcome>,
) {
    val blockedRoots: List<RootOutcome> get() = roots.filter { it.blocked != null || it.rootProblem != null }
}

/** Events of [FileScanner] flows: zero or more [Progress], then exactly one terminal event. */
sealed interface ScanEvent {
    data class Progress(val progress: ScanProgress) : ScanEvent

    /** Terminal: the filter is invalid; nothing was walked. */
    data class InvalidFilter(val problems: List<FilterProblem>) : ScanEvent

    /** Terminal: the scan finished (possibly with blocked roots, see [ScanResult.roots]). */
    data class Completed(val result: ScanResult) : ScanEvent
}
