package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.di.IoDispatcher
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.FileFilter
import ca.devdcs.backup.domain.model.PathTarget
import ca.devdcs.backup.domain.model.TargetSelection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entry point for file discovery: turns [PathTarget]s and [FileCategory] sets into filtered file
 * lists (for the backup pipeline) or bounded summaries (for previews). It validates filters up
 * front, classifies each root, picks the walker the current tier and permissions allow
 * (NIO / SAF / privileged find / MediaStore), and reports roots it cannot read together with the
 * permission or tier that would unlock them, instead of silently returning less.
 *
 * All flows are cold; cancelling the collector stops the walk (and kills a privileged `find`).
 */
@Singleton
class FileScanner @Inject constructor(
    private val inspector: StorageAccessInspector,
    private val nioWalker: NioFileWalker,
    private val safWalker: SafFileWalker,
    private val findWalker: PrivilegedFindWalker,
    private val mediaStoreWalker: MediaStoreFileWalker,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Scan one path or SAF location. */
    fun scan(target: PathTarget, options: ScanOptions = ScanOptions()): Flow<ScanEvent> =
        scanSelection(TargetSelection(paths = listOf(target)), options)

    /**
     * Scan the standard shared-storage roots of [categories] (DCIM, Pictures, Movies, Music,
     * Download, Documents) for their default extensions, ANDed with [filter].
     */
    fun scanCategories(
        categories: Set<FileCategory>,
        filter: FileFilter? = null,
        options: ScanOptions = ScanOptions(),
    ): Flow<ScanEvent> = scanInternal(emptyList(), categories, filter, options)

    /** Scan every path and category of a profile selection (partitions and apps are ignored). */
    fun scanSelection(selection: TargetSelection, options: ScanOptions = ScanOptions()): Flow<ScanEvent> =
        scanInternal(selection.paths, selection.categories, null, options)

    /** Runs [scanSelection] to completion and returns its terminal event (Completed or InvalidFilter). */
    suspend fun run(selection: TargetSelection, options: ScanOptions = ScanOptions()): ScanEvent =
        scanSelection(selection, options).last()

    /** Pre-flight for a picker: which walker [location] would use now, or what is missing. */
    suspend fun checkAccess(location: String): WalkerDecision {
        val ctx = inspector.snapshot()
        return decideForPath(location, ctx)
    }

    private fun scanInternal(
        paths: List<PathTarget>,
        categories: Set<FileCategory>,
        categoryFilter: FileFilter?,
        options: ScanOptions,
    ): Flow<ScanEvent> = flow {
        val problems = paths.flatMap { (it.filter ?: FileFilter.NONE).validate() } +
            (categoryFilter ?: FileFilter.NONE).validate()
        if (problems.isNotEmpty()) {
            emit(ScanEvent.InvalidFilter(problems.distinct()))
            return@flow
        }
        val ctx = inspector.snapshot()
        val exclusions = ExclusionMatcher.of(options.useDefaultExclusions)
        val planned = paths.map { PlannedRoot(it.location, it.recursive, it.filter ?: FileFilter.NONE) } +
            CategoryRoots.plan(categories, inspector.primaryStoragePath, categoryFilter)
        val resolved = planned.distinct().map { resolve(it, ctx, exclusions, categoryFilter) }
        emitAll(ScanExecution.execute(resolved, options, primaryStorage = inspector.primaryStoragePath))
    }.flowOn(io)

    private suspend fun resolve(
        root: PlannedRoot,
        ctx: AccessContext,
        exclusions: ExclusionMatcher,
        categoryFilter: FileFilter?,
    ): ResolvedRoot {
        if (root.categories.isNotEmpty()) return resolveCategoryRoot(root, ctx, exclusions, categoryFilter)
        return when (val decision = decideForPath(root.location, ctx)) {
            is WalkerDecision.Blocked -> ResolvedRoot(
                root, null, null,
                RootOutcome(root.location, walker = null, blocked = decision.reason, requirements = decision.requirements),
            )
            is WalkerDecision.Use -> {
                // NIO cannot see other apps' Android/data|obb on API 30+: count them as unreadable.
                val hidden = if (decision.walker == WalkerKind.NIO && ctx.sdkInt > WalkerSelector.API_Q && root.recursive) {
                    LocationClassifier.appSandboxDirsBelow(root.location).filterNot(exclusions::excludesDirectory).toSet()
                } else {
                    emptySet()
                }
                ResolvedRoot(
                    root,
                    walkerFor(decision.walker),
                    WalkSpec(root.location, root.recursive, exclusions, hiddenDirs = hidden),
                    RootOutcome(root.location, walker = decision.walker),
                )
            }
        }
    }

    private fun resolveCategoryRoot(
        root: PlannedRoot,
        ctx: AccessContext,
        exclusions: ExclusionMatcher,
        categoryFilter: FileFilter?,
    ): ResolvedRoot {
        val decision = WalkerSelector.selectForCategoryRoot(root.categories, ctx)
        val kind = decision.walker
        val outcome = RootOutcome(
            location = root.location,
            walker = kind,
            blocked = decision.reason,
            requirements = decision.requirements,
            uncoveredCategories = decision.uncovered,
            warnings = if (kind == WalkerKind.MEDIA_STORE && ctx.partialVisualAccess &&
                (FileCategory.IMAGES in decision.covered || FileCategory.VIDEOS in decision.covered)
            ) {
                setOf(ScanWarning.PARTIAL_MEDIA_ACCESS)
            } else {
                emptySet()
            },
        )
        if (kind == null) return ResolvedRoot(root, null, null, outcome)
        val effective = if (decision.covered == root.categories) root else {
            root.copy(filter = CategoryRoots.effectiveFilter(decision.covered, categoryFilter), categories = decision.covered)
        }
        return ResolvedRoot(
            effective,
            walkerFor(kind),
            WalkSpec(root.location, root.recursive, exclusions, mediaCategories = decision.covered),
            outcome,
        )
    }

    private suspend fun decideForPath(location: String, ctx: AccessContext): WalkerDecision {
        val locationClass = LocationClassifier.classify(location, inspector.ownPackage)
        if (locationClass == LocationClass.SAF && !inspector.hasSafReadGrant(location)) {
            return WalkerDecision.Blocked(BlockReason.SAF_PERMISSION_MISSING, listOf(AccessRequirement.SafRegrant))
        }
        val readable = locationClass != LocationClass.OTHER || inspector.readableInProcess(location)
        val sandbox = LocationClassifier.appSandboxDirsBelow(location).isNotEmpty()
        return WalkerSelector.select(locationClass, ctx, readable, containsAppSandbox = sandbox)
    }

    private fun walkerFor(kind: WalkerKind): FileWalker = when (kind) {
        WalkerKind.NIO -> nioWalker
        WalkerKind.SAF -> safWalker
        WalkerKind.PRIVILEGED_FIND -> findWalker
        WalkerKind.MEDIA_STORE -> mediaStoreWalker
    }
}
