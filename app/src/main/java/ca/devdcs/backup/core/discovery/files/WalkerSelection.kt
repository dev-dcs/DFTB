package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.FileFilter

/** Where a location lives, which determines who is allowed to read it on Android 10+. */
enum class LocationClass {
    /** `content://` tree or document URI from the system picker. */
    SAF,

    /** This app's own `/data/data|user|user_de/<pkg>` directory. */
    APP_PRIVATE,

    /** This app's own `Android/data|obb/<pkg>` on shared storage. */
    APP_EXTERNAL_OWN,

    /** Shared storage (`/storage/emulated/N`, `/sdcard`, SD cards) outside `Android/data|obb`. */
    SHARED_STORAGE,

    /** Other apps' `Android/data` / `Android/obb` (scoped-storage protected on API 30+). */
    SHARED_APP_SANDBOX,

    /** Anything else under `/data` (other apps' private data, `/data/media`, ...). */
    DATA_PARTITION,

    /** Everything else (`/system`, `/vendor`, `/mnt`, ...). */
    OTHER,
}

/**
 * What the app is currently allowed to do, captured once per scan by [StorageAccessInspector] and
 * passed to the pure [WalkerSelector]. [mediaGranted] lists the media categories whose runtime
 * permission is held (all three when READ_EXTERNAL_STORAGE is granted below API 33).
 */
data class AccessContext(
    val sdkInt: Int,
    val tier: ElevationTier,
    /** The tier's executor answered [ca.devdcs.backup.core.exec.CommandExecutor.isAvailable]. */
    val privilegedAvailable: Boolean,
    val allFilesAccess: Boolean,
    /** API 29 legacy storage in effect with READ_EXTERNAL_STORAGE granted. */
    val legacyStorageReadable: Boolean,
    val mediaGranted: Set<FileCategory> = emptySet(),
    val partialVisualAccess: Boolean = false,
) {
    val canUseFind: Boolean get() = privilegedAvailable && tier != ElevationTier.NONE
    val sharedStorageReadable: Boolean get() = allFilesAccess || legacyStorageReadable
}

/** Outcome of walker selection for a single root. */
sealed interface WalkerDecision {
    data class Use(val walker: WalkerKind) : WalkerDecision
    data class Blocked(val reason: BlockReason, val requirements: List<AccessRequirement>) : WalkerDecision
}

/**
 * Outcome for a category root: possibly MediaStore covers only some categories (e.g. images granted,
 * videos not), so the uncovered ones are reported with what would unlock them.
 */
data class CategoryRootDecision(
    val walker: WalkerKind?,
    val covered: Set<FileCategory>,
    val uncovered: Set<FileCategory>,
    val reason: BlockReason?,
    val requirements: List<AccessRequirement>,
)

/** Pure location classification. */
object LocationClassifier {

    private val SHARED_ROOT = Regex("^(/storage/emulated/\\d+|/sdcard|/storage/self/primary|/mnt/sdcard|/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(/.*)?$")
    private val APP_DATA_ROOT = Regex("^/data/(data|user/\\d+|user_de/\\d+)/([^/]+)(/.*)?$")

    /** Collapses duplicate slashes and strips a trailing slash (keeps "/" as is). */
    fun normalize(path: String): String {
        val collapsed = path.replace(Regex("/{2,}"), "/")
        return if (collapsed.length > 1) collapsed.trimEnd('/') else collapsed
    }

    /**
     * Comparable form of [location]: normalized, with the primary-storage aliases (`/sdcard`,
     * `/storage/self/primary`, `/mnt/sdcard`) rewritten to [primaryStorage]. URIs are unchanged.
     */
    fun canonicalize(location: String, primaryStorage: String): String {
        if (location.startsWith("content://")) return location
        val path = normalize(location)
        val primary = normalize(primaryStorage)
        for (alias in PRIMARY_ALIASES) {
            if (path == alias) return primary
            if (path.startsWith("$alias/")) return primary + path.substring(alias.length)
        }
        return path
    }

    private val PRIMARY_ALIASES = listOf("/sdcard", "/storage/self/primary", "/mnt/sdcard")

    /**
     * Root-relative paths of `Android/data` and `Android/obb` when [location] is a shared-storage
     * root whose subtree contains them (the volume root or its `Android` dir), else empty. On API
     * 30+ these are hidden from in-process listing even with All files access.
     */
    fun appSandboxDirsBelow(location: String): List<String> {
        if (location.startsWith("content://")) return emptyList()
        val match = SHARED_ROOT.matchEntire(normalize(location)) ?: return emptyList()
        val below = match.groupValues[2].trimStart('/').split('/').filter { it.isNotEmpty() }
        return when {
            below.isEmpty() -> listOf("Android/data", "Android/obb")
            below == listOf("Android") -> listOf("data", "obb")
            else -> emptyList()
        }
    }

    fun classify(location: String, ownPackage: String): LocationClass {
        if (location.startsWith("content://")) return LocationClass.SAF
        val path = normalize(location)
        SHARED_ROOT.matchEntire(path)?.let { match ->
            val below = match.groupValues[2].trimStart('/').split('/').filter { it.isNotEmpty() }
            if (below.size >= 2 && below[0] == "Android" && (below[1] == "data" || below[1] == "obb")) {
                return if (below.size >= 3 && below[2] == ownPackage) LocationClass.APP_EXTERNAL_OWN else LocationClass.SHARED_APP_SANDBOX
            }
            return LocationClass.SHARED_STORAGE
        }
        APP_DATA_ROOT.matchEntire(path)?.let { match ->
            return if (match.groupValues[2] == ownPackage) LocationClass.APP_PRIVATE else LocationClass.DATA_PARTITION
        }
        if (path == "/data" || path.startsWith("/data/")) return LocationClass.DATA_PARTITION
        return LocationClass.OTHER
    }
}

/**
 * Pure walker choice: prefer the cheap in-process NIO walk whenever the app itself may read the
 * location, use one privileged `find` when only the elevated shell may, and otherwise say exactly
 * which permission or tier is missing.
 */
object WalkerSelector {

    /**
     * @param containsAppSandbox the root's subtree includes `Android/data|obb`
     * ([LocationClassifier.appSandboxDirsBelow]); on API 30+ only find can list those, so it is
     * preferred over NIO when available.
     */
    fun select(
        location: LocationClass,
        ctx: AccessContext,
        rootReadableInProcess: Boolean = true,
        containsAppSandbox: Boolean = false,
    ): WalkerDecision = when (location) {
        LocationClass.SAF -> WalkerDecision.Use(WalkerKind.SAF)
        LocationClass.APP_PRIVATE, LocationClass.APP_EXTERNAL_OWN -> WalkerDecision.Use(WalkerKind.NIO)
        LocationClass.SHARED_STORAGE -> when {
            containsAppSandbox && ctx.sdkInt > API_Q && ctx.canUseFind -> WalkerDecision.Use(WalkerKind.PRIVILEGED_FIND)
            ctx.sharedStorageReadable -> WalkerDecision.Use(WalkerKind.NIO)
            ctx.canUseFind -> WalkerDecision.Use(WalkerKind.PRIVILEGED_FIND)
            else -> WalkerDecision.Blocked(
                BlockReason.NEEDS_ALL_FILES_ACCESS,
                listOf(storageRequirement(ctx.sdkInt), AccessRequirement.ElevatedTier(ElevationTier.SHIZUKU)),
            )
        }
        LocationClass.SHARED_APP_SANDBOX -> when {
            // API 29 legacy storage could still read other apps' Android/data.
            ctx.sdkInt <= API_Q && ctx.sharedStorageReadable -> WalkerDecision.Use(WalkerKind.NIO)
            ctx.canUseFind -> WalkerDecision.Use(WalkerKind.PRIVILEGED_FIND)
            else -> WalkerDecision.Blocked(
                BlockReason.NEEDS_ELEVATION_FOR_APP_SANDBOX,
                listOf(AccessRequirement.ElevatedTier(ElevationTier.SHIZUKU)),
            )
        }
        LocationClass.DATA_PARTITION ->
            if (ctx.privilegedAvailable && ctx.tier == ElevationTier.ROOT) {
                WalkerDecision.Use(WalkerKind.PRIVILEGED_FIND)
            } else {
                WalkerDecision.Blocked(BlockReason.NEEDS_ROOT, listOf(AccessRequirement.ElevatedTier(ElevationTier.ROOT)))
            }
        LocationClass.OTHER -> when {
            ctx.canUseFind -> WalkerDecision.Use(WalkerKind.PRIVILEGED_FIND)
            rootReadableInProcess -> WalkerDecision.Use(WalkerKind.NIO)
            else -> WalkerDecision.Blocked(BlockReason.NOT_READABLE, listOf(AccessRequirement.ElevatedTier(ElevationTier.SHIZUKU)))
        }
    }

    /**
     * Choice for a standard media/document root (DCIM, Download...) on primary shared storage.
     * Standard tier without All files access falls back to MediaStore, which only indexes media the
     * user granted; non-media categories then need All files access (or an elevated tier).
     */
    fun selectForCategoryRoot(categories: Set<FileCategory>, ctx: AccessContext): CategoryRootDecision {
        when (val decision = select(LocationClass.SHARED_STORAGE, ctx)) {
            is WalkerDecision.Use -> return CategoryRootDecision(decision.walker, categories, emptySet(), null, emptyList())
            is WalkerDecision.Blocked -> Unit
        }
        val media = categories intersect MEDIA_CATEGORIES
        val covered = media intersect ctx.mediaGranted
        val uncovered = categories - covered
        val missingMedia = media - ctx.mediaGranted
        val requirements = ArrayList<AccessRequirement>()
        val reason = when {
            uncovered.isEmpty() -> null
            missingMedia.isNotEmpty() -> BlockReason.NEEDS_MEDIA_PERMISSION
            else -> BlockReason.NEEDS_ALL_FILES_ACCESS_FOR_NON_MEDIA
        }
        if (missingMedia.isNotEmpty()) requirements += AccessRequirement.RuntimePermission(mediaPermissions(missingMedia, ctx.sdkInt))
        if (uncovered.isNotEmpty()) {
            requirements += storageRequirement(ctx.sdkInt)
            requirements += AccessRequirement.ElevatedTier(ElevationTier.SHIZUKU)
        }
        return CategoryRootDecision(
            walker = if (covered.isNotEmpty()) WalkerKind.MEDIA_STORE else null,
            covered = covered,
            uncovered = uncovered,
            reason = reason,
            requirements = requirements.distinct(),
        )
    }

    /** API 30+: All files access; API 29: the legacy READ_EXTERNAL_STORAGE grant. */
    fun storageRequirement(sdkInt: Int): AccessRequirement =
        if (sdkInt > API_Q) AccessRequirement.AllFilesAccess() else AccessRequirement.RuntimePermission(listOf(READ_EXTERNAL_STORAGE))

    /** Runtime permissions that grant MediaStore visibility of [categories] on [sdkInt]. */
    fun mediaPermissions(categories: Set<FileCategory>, sdkInt: Int): List<String> {
        if (sdkInt < API_TIRAMISU) return listOf(READ_EXTERNAL_STORAGE)
        return categories.sortedBy { it.ordinal }.mapNotNull {
            when (it) {
                FileCategory.IMAGES -> READ_MEDIA_IMAGES
                FileCategory.VIDEOS -> READ_MEDIA_VIDEO
                FileCategory.AUDIO -> READ_MEDIA_AUDIO
                else -> null
            }
        }
    }

    val MEDIA_CATEGORIES: Set<FileCategory> = setOf(FileCategory.IMAGES, FileCategory.VIDEOS, FileCategory.AUDIO)

    const val API_Q = 29
    const val API_TIRAMISU = 33
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
}

/** One root to walk with the filter that applies to it. */
data class PlannedRoot(
    val location: String,
    val recursive: Boolean,
    val filter: FileFilter,
    /** Non-empty for category-scan roots (drives the MediaStore fallback). */
    val categories: Set<FileCategory> = emptySet(),
)

/** Pure mapping from [FileCategory] sets to standard shared-storage roots and filters. */
object CategoryRoots {

    /** Standard top-level directories (relative to primary shared storage) each category lives in. */
    fun rootsFor(category: FileCategory): List<String> = when (category) {
        FileCategory.IMAGES -> listOf("DCIM", "Pictures")
        FileCategory.VIDEOS -> listOf("DCIM", "Movies")
        FileCategory.AUDIO -> listOf("Music")
        FileCategory.DOCUMENTS -> listOf("Documents", "Download")
        FileCategory.ARCHIVES, FileCategory.BINARIES, FileCategory.DATABASES, FileCategory.CONFIGS -> listOf("Download", "Documents")
    }

    /**
     * One [PlannedRoot] per distinct directory, whose filter includes the union of the categories'
     * default extensions ANDed with [userFilter] (an explicit include list is intersected).
     */
    fun plan(categories: Set<FileCategory>, primaryStorage: String, userFilter: FileFilter?): List<PlannedRoot> {
        val byRoot = LinkedHashMap<String, MutableSet<FileCategory>>()
        for (category in categories.sortedBy { it.ordinal }) {
            for (root in rootsFor(category)) byRoot.getOrPut(root) { LinkedHashSet() }.add(category)
        }
        val base = LocationClassifier.normalize(primaryStorage)
        return byRoot.map { (dir, cats) ->
            PlannedRoot(
                location = "$base/$dir",
                recursive = true,
                filter = effectiveFilter(cats, userFilter),
                categories = cats,
            )
        }
    }

    fun effectiveFilter(categories: Set<FileCategory>, userFilter: FileFilter?): FileFilter {
        val categoryExtensions = categories.flatMapTo(LinkedHashSet()) { it.defaultExtensions }
        val base = userFilter ?: FileFilter.NONE
        val userIncludes = base.includeExtensions.map(FileFilter::normalizeExtension).toSet()
        val include = if (userIncludes.isEmpty()) categoryExtensions else categoryExtensions intersect userIncludes
        // An empty intersection must match nothing, not everything: use an impossible extension.
        return base.copy(includeExtensions = include.ifEmpty { setOf(NO_MATCH_EXTENSION) })
    }

    /** Contains '/', so FileFilter.extensionOf can never produce it. */
    const val NO_MATCH_EXTENSION = "/"
}
