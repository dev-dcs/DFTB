package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.domain.model.CompiledFileFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A planned root with its chosen walker (null when blocked) and the outcome fields known before
 * walking (block reason, requirements, uncovered categories, pre-walk warnings).
 */
data class ResolvedRoot(
    val planned: PlannedRoot,
    val walker: FileWalker?,
    val spec: WalkSpec?,
    val outcome: RootOutcome,
)

/**
 * Drives walkers over resolved roots and folds their events into progress + one [ScanResult].
 * Free of Android types so it is unit-testable with fake walkers; [FileScanner] does the planning.
 * Filters must already be validated (they are compiled here).
 *
 * Overlapping roots (e.g. `/sdcard` and `/storage/emulated/0/DCIM`) are de-duplicated without
 * remembering matched paths: while walking an outer root, a file is left to a nested (or an
 * earlier identical) root whenever that root's own filter would match it, so memory stays
 * constant regardless of the number of files. If the nested root later fails at run time, the
 * files it would have covered are missing from the result; its [RootOutcome] reports the failure.
 */
object ScanExecution {

    /** A walkable root lying inside the one being walked; [prefix] is its path below it ("" = same). */
    private class Cover(val prefix: String, val recursive: Boolean, val filter: CompiledFileFilter)

    fun execute(
        roots: List<ResolvedRoot>,
        options: ScanOptions,
        nanoClock: () -> Long = System::nanoTime,
        primaryStorage: String = DEFAULT_PRIMARY,
    ): Flow<ScanEvent> = flow {
        val aggregator = ScanAggregator(options.topN, options.collectFiles, options.maxCollectedFiles)
        val outcomes = ArrayList<RootOutcome>(roots.size)
        val compiled = roots.map { it.planned.filter.compile() }
        val canonical = roots.map { LocationClassifier.canonicalize(it.planned.location, primaryStorage) }
        val intervalNanos = options.progressIntervalMillis.coerceAtLeast(0) * 1_000_000
        var lastProgress = nanoClock()
        var current: String? = null

        suspend fun maybeProgress() {
            val now = nanoClock()
            if (now - lastProgress >= intervalNanos) {
                lastProgress = now
                emit(ScanEvent.Progress(aggregator.progress(current)))
            }
        }

        for ((index, root) in roots.withIndex()) {
            val walker = root.walker
            val spec = root.spec
            if (walker == null || spec == null) {
                outcomes += root.outcome
                continue
            }
            val filter = compiled[index]
            val covers = roots.indices.mapNotNull { other ->
                if (other == index || roots[other].walker == null || roots[other].spec == null) return@mapNotNull null
                val prefix = nestedPrefix(canonical[index], canonical[other], otherIsEarlier = other < index)
                    ?: return@mapNotNull null
                Cover(prefix, roots[other].planned.recursive, compiled[other])
            }
            val exclusions = spec.exclusions
            val base = root.planned.location
            val isUri = base.startsWith("content://")
            var rootProblem: RootProblem? = null
            val warnings = LinkedHashSet(root.outcome.warnings)
            walker.walk(spec).collect { event ->
                when (event) {
                    is WalkEvent.Directory -> {
                        val rel = event.relativePath
                        if (!exclusions.excludesDirectory(rel) && covers.none { it.coversDirectory(rel) }) {
                            aggregator.onDirectory()
                            current = displayPath(base, rel, isUri)
                            maybeProgress()
                        }
                    }
                    is WalkEvent.File -> {
                        val rel = event.relativePath
                        if (!exclusions.excludesFile(rel) &&
                            filter.matches(rel, event.sizeBytes, event.modifiedAtMillis) &&
                            covers.none { it.coversFile(rel, event.sizeBytes, event.modifiedAtMillis, exclusions) }
                        ) {
                            aggregator.onMatch(
                                ScannedFile(base, rel, event.location, event.sizeBytes, event.modifiedAtMillis, walker.kind),
                            )
                        }
                        maybeProgress()
                    }
                    is WalkEvent.Unreadable -> aggregator.onUnreadable()
                    is WalkEvent.RootUnavailable -> {
                        // A standard category folder (e.g. Movies) that does not exist is not a problem.
                        val benign = event.problem == RootProblem.NOT_FOUND && root.planned.categories.isNotEmpty()
                        if (rootProblem == null && !benign) rootProblem = event.problem
                    }
                    is WalkEvent.Warning -> warnings += event.warning
                }
            }
            outcomes += root.outcome.copy(rootProblem = rootProblem, warnings = warnings)
        }
        emit(ScanEvent.Progress(aggregator.progress(current)))
        emit(
            ScanEvent.Completed(
                ScanResult(
                    summary = aggregator.summary(),
                    files = aggregator.files(),
                    truncated = aggregator.truncated,
                    roots = outcomes,
                ),
            ),
        )
    }

    private fun Cover.relativeToCover(rel: String): String? = when {
        prefix.isEmpty() -> rel
        rel == prefix -> prefix.substringAfterLast('/') // the nested root is this single file
        rel.startsWith("$prefix/") -> rel.substring(prefix.length + 1)
        else -> null
    }

    private fun Cover.coversFile(rel: String, size: Long, mtime: Long, exclusions: ExclusionMatcher): Boolean {
        val inner = relativeToCover(rel) ?: return false
        if (!recursive && rel != prefix && inner.contains('/')) return false
        if (exclusions.excludesFile(inner)) return false
        return filter.matches(inner, size, mtime)
    }

    private fun Cover.coversDirectory(rel: String): Boolean =
        recursive && (prefix.isEmpty() || rel == prefix || rel.startsWith("$prefix/"))

    /**
     * Path of [other] below [root] when [other] is strictly nested in it, "" when both are the same
     * location and [other] comes first (so exactly one of two identical roots owns the files),
     * otherwise null. Both arguments must be canonical ([LocationClassifier.canonicalize]).
     */
    internal fun nestedPrefix(root: String, other: String, otherIsEarlier: Boolean): String? {
        if (root == other) return if (otherIsEarlier) "" else null
        if (root.startsWith("content://") || other.startsWith("content://")) return null
        if (root == "/") return other.trimStart('/').ifEmpty { null }
        return if (other.startsWith("$root/")) other.substring(root.length + 1) else null
    }

    /** True when some root equals or contains another after alias canonicalization. */
    fun rootsOverlap(locations: List<String>, primaryStorage: String = DEFAULT_PRIMARY): Boolean {
        val canonical = locations.map { LocationClassifier.canonicalize(it, primaryStorage) }
        for (i in canonical.indices) {
            for (j in canonical.indices) {
                if (i != j && nestedPrefix(canonical[i], canonical[j], otherIsEarlier = true) != null) return true
            }
        }
        return false
    }

    private fun displayPath(base: String, rel: String, isUri: Boolean): String = when {
        rel.isEmpty() -> base
        isUri -> rel
        else -> base.trimEnd('/') + "/" + rel
    }

    /** Primary storage of user 0; [FileScanner] passes the real path. */
    const val DEFAULT_PRIMARY = "/storage/emulated/0"
}
