package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.domain.model.FileCategory
import kotlinx.coroutines.flow.Flow

/**
 * One raw observation from a walker, before filtering. Walkers never throw for environment limits;
 * they report [RootUnavailable] / [Unreadable] instead so the scanner can explain partial results.
 */
sealed interface WalkEvent {
    /** A directory was entered ("" = the root). */
    data class Directory(val relativePath: String) : WalkEvent

    /** A regular file (symlinks are never reported). [location] is what the pipeline opens. */
    data class File(
        val relativePath: String,
        val location: String,
        val sizeBytes: Long,
        val modifiedAtMillis: Long,
    ) : WalkEvent

    /** An entry below the root could not be read; counted in [ScanSummary.skippedUnreadable]. */
    data class Unreadable(val path: String, val reason: String) : WalkEvent

    /** The root itself could not be walked; terminal for this root. */
    data class RootUnavailable(val problem: RootProblem, val detail: String) : WalkEvent

    data class Warning(val warning: ScanWarning, val detail: String) : WalkEvent
}

/**
 * What to walk. [mediaCategories] is only read by the MediaStore walker. [hiddenDirs] are
 * root-relative directories the NIO walker must report as unreadable instead of listing (scoped
 * storage shows `Android/data|obb` as empty on API 30+, which would otherwise look like "no files").
 */
data class WalkSpec(
    val rootLocation: String,
    val recursive: Boolean,
    val exclusions: ExclusionMatcher,
    val mediaCategories: Set<FileCategory> = emptySet(),
    val hiddenDirs: Set<String> = emptySet(),
)

/**
 * Common shape of the four enumeration strategies, so [FileScanner] filters and aggregates the same
 * way whatever produced the entries. Flows are cold, run their blocking work on the IO dispatcher,
 * honour cancellation between entries, and suspend on emit (natural backpressure).
 */
interface FileWalker {
    val kind: WalkerKind
    fun walk(spec: WalkSpec): Flow<WalkEvent>
}
