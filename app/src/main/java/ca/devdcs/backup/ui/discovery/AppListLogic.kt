package ca.devdcs.backup.ui.discovery

import ca.devdcs.backup.core.discovery.apps.AppKind
import ca.devdcs.backup.core.discovery.apps.AppSizeInfo
import ca.devdcs.backup.core.discovery.apps.InstalledApp

/** Orderings offered by the app list. */
enum class AppSort {
    /** Label, locale-aware, then package name. */
    NAME,

    /** Largest known total first; apps without a measurement go last. */
    SIZE,

    /** Most recently installed/updated first. */
    UPDATED,
}

/**
 * Filter chips of the app list. The three kind chips are OR-ed with each other (picking "User"
 * and "Updated system" shows both); [SPLIT] and [HAS_OBB] narrow the result further (AND).
 */
enum class AppListFilter {
    USER,
    SYSTEM,
    UPDATED_SYSTEM,
    SPLIT,
    HAS_OBB,
}

/** Everything the user typed or toggled to narrow and order the app list. */
data class AppListQuery(
    val search: String = "",
    val filters: Set<AppListFilter> = emptySet(),
    val sort: AppSort = AppSort.NAME,
)

/**
 * Pure search / filter / sort of the installed-app list. Lives outside the ViewModel so the exact
 * rules (OR between kinds, AND for split/OBB, unknown sizes last) are unit-tested.
 */
object AppListLogic {

    /**
     * Returns [apps] narrowed and ordered by [query]. [sizes] feeds [AppSort.SIZE]; [nameOrder]
     * compares labels (callers pass a locale Collator; tests use the case-insensitive default).
     */
    fun apply(
        apps: List<InstalledApp>,
        sizes: Map<String, AppSizeInfo>,
        query: AppListQuery,
        nameOrder: Comparator<String> = String.CASE_INSENSITIVE_ORDER,
    ): List<InstalledApp> {
        val byName = Comparator<InstalledApp> { a, b -> nameOrder.compare(a.label, b.label) }.thenBy { it.packageName }
        val comparator: Comparator<InstalledApp> = when (query.sort) {
            AppSort.NAME -> byName
            AppSort.SIZE -> Comparator<InstalledApp> { a, b ->
                val sa = sizes[a.packageName]?.totalKnownBytes
                val sb = sizes[b.packageName]?.totalKnownBytes
                when {
                    sa == null && sb == null -> 0
                    sa == null -> 1
                    sb == null -> -1
                    else -> sb.compareTo(sa)
                }
            }.then(byName)
            AppSort.UPDATED -> compareByDescending<InstalledApp> { it.lastUpdateTimeMillis }.then(byName)
        }
        return apps.filter { matches(it, query) }.sortedWith(comparator)
    }

    /** True when [app] passes the search text and every active filter chip. */
    fun matches(app: InstalledApp, query: AppListQuery): Boolean {
        val needle = query.search.trim()
        if (needle.isNotEmpty() &&
            !app.label.contains(needle, ignoreCase = true) &&
            !app.packageName.contains(needle, ignoreCase = true)
        ) {
            return false
        }
        val kinds = query.filters.mapNotNullTo(HashSet()) { kindOf(it) }
        if (kinds.isNotEmpty() && app.kind !in kinds) return false
        if (AppListFilter.SPLIT in query.filters && !app.isSplitBundle) return false
        if (AppListFilter.HAS_OBB in query.filters && app.flags.hasObb != true) return false
        return true
    }

    /** Toggles [filter] in [filters]; returns the new set. */
    fun toggle(filters: Set<AppListFilter>, filter: AppListFilter): Set<AppListFilter> =
        if (filter in filters) filters - filter else filters + filter

    private fun kindOf(filter: AppListFilter): AppKind? = when (filter) {
        AppListFilter.USER -> AppKind.USER
        AppListFilter.SYSTEM -> AppKind.SYSTEM
        AppListFilter.UPDATED_SYSTEM -> AppKind.UPDATED_SYSTEM
        AppListFilter.SPLIT, AppListFilter.HAS_OBB -> null
    }
}
