package ca.devdcs.backup.ui.discovery

import android.graphics.Bitmap
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.devdcs.backup.core.discovery.apps.AppInspector
import ca.devdcs.backup.core.discovery.apps.AppScanOptions
import ca.devdcs.backup.core.discovery.apps.AppScanResult
import ca.devdcs.backup.core.discovery.apps.AppScanWarning
import ca.devdcs.backup.core.discovery.apps.AppScanWarningKind
import ca.devdcs.backup.core.discovery.apps.AppSizeInfo
import ca.devdcs.backup.core.discovery.apps.ComponentAvailability
import ca.devdcs.backup.core.discovery.apps.InstalledApp
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.domain.model.AppComponent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Collections
import javax.inject.Inject

/** Progress of a chunked [AppInspector.measureSizes] run. */
data class SizeMeasureProgress(val done: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/** Apps tab state. [scan] is null until the first listing returns. */
data class AppsUiState(
    val loading: Boolean = true,
    val scan: AppScanResult? = null,
    /** All listed apps, with OBB presence refined by any size measurement. */
    val apps: List<InstalledApp> = emptyList(),
    /** [apps] after [query]; what the list shows. */
    val visibleApps: List<InstalledApp> = emptyList(),
    val query: AppListQuery = AppListQuery(),
    val sizes: Map<String, AppSizeInfo> = emptyMap(),
    val measuring: SizeMeasureProgress? = null,
    /** Warnings from the listing and from the last measurement, de-duplicated. */
    val warnings: List<AppScanWarning> = emptyList(),
    /** Re-checked on resume so returning from Settings updates the prompt. */
    val usageAccessGranted: Boolean = false,
    val availability: Map<AppComponent, ComponentAvailability> = emptyMap(),
    /** The listing itself threw (not an ordinary limitation). */
    val failed: Boolean = false,
)

/**
 * Backs the installed-app browser: lists packages once, measures sizes in chunks so the user sees
 * progress and can cancel, applies [AppListLogic] for search/filter/sort, and serves row icons from
 * a bounded in-memory cache (rasterised off the main thread by [AppInspector.loadIconBitmap]).
 */
@HiltViewModel
class AppsViewModel @Inject constructor(
    private val inspector: AppInspector,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppsUiState())
    val uiState: StateFlow<AppsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var measureJob: Job? = null
    private val collator: Collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
    private val nameOrder = Comparator<String> { a, b -> collator.compare(a, b) }

    private val iconCache = object : LruCache<String, Bitmap>(ICON_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missingIcons: MutableSet<String> = Collections.synchronizedSet(HashSet())

    init {
        refresh()
    }

    /** Re-lists installed apps; keeps sizes already measured for packages that still exist. */
    fun refresh() {
        if (loadJob?.isActive == true) return
        _uiState.update { it.copy(loading = true, failed = false) }
        loadJob = viewModelScope.launch {
            try {
                val scan = inspector.scan(AppScanOptions(includeSystemApps = true, includeSizes = false))
                _uiState.update { state ->
                    val known = scan.apps.mapTo(HashSet()) { it.packageName }
                    state.copy(
                        loading = false,
                        scan = scan,
                        apps = scan.apps,
                        sizes = state.sizes.filterKeys { it in known },
                        usageAccessGranted = scan.usageAccessGranted,
                        availability = scan.componentAvailability,
                        warnings = dedupe(scan.warnings),
                    ).withVisible()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.e(TAG, "App listing failed", e)
                _uiState.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    /** Cheap re-check of usage access and component availability (e.g. back from Settings). */
    fun refreshAccess() {
        viewModelScope.launch {
            try {
                val granted = inspector.isUsageAccessGranted()
                val availability = inspector.componentAvailability()
                _uiState.update { it.copy(usageAccessGranted = granted, availability = availability) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.w(TAG, "Access re-check failed", e)
            }
        }
    }

    fun setSearch(text: String) = updateQuery { it.copy(search = text) }

    fun toggleFilter(filter: AppListFilter) = updateQuery { it.copy(filters = AppListLogic.toggle(it.filters, filter)) }

    fun setSort(sort: AppSort) = updateQuery { it.copy(sort = sort) }

    /** Measures every listed app, [MEASURE_CHUNK] packages per engine call, publishing results as they arrive. */
    fun measureSizes() {
        val apps = _uiState.value.apps
        if (apps.isEmpty() || measureJob?.isActive == true) return
        _uiState.update { it.copy(measuring = SizeMeasureProgress(0, apps.size)) }
        measureJob = viewModelScope.launch {
            val newWarnings = ArrayList<AppScanWarning>()
            try {
                var done = 0
                for (chunk in apps.chunked(MEASURE_CHUNK)) {
                    val report = inspector.measureSizes(chunk)
                    done += chunk.size
                    newWarnings += report.warnings
                    _uiState.update { state ->
                        val refined = if (report.obbPresence.isEmpty()) state.apps else state.apps.map { app ->
                            val obb = report.obbPresence[app.packageName]
                            if (obb != null && app.flags.hasObb != obb) app.copy(flags = app.flags.copy(hasObb = obb)) else app
                        }
                        state.copy(
                            apps = refined,
                            sizes = state.sizes + report.sizes,
                            usageAccessGranted = report.usageAccessGranted,
                            measuring = SizeMeasureProgress(done, apps.size),
                        ).withVisible()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DftbLog.e(TAG, "Size measurement failed", e)
                newWarnings += AppScanWarning(AppScanWarningKind.STATS_QUERY_FAILED, detail = e.message)
            } finally {
                val listing = _uiState.value.scan?.warnings.orEmpty()
                _uiState.update { it.copy(measuring = null, warnings = dedupe(listing + newWarnings)) }
            }
        }
    }

    fun cancelMeasure() {
        measureJob?.cancel()
    }

    /** Synchronous cache hit for the first frame of a row; null when not cached yet. */
    fun cachedIcon(packageName: String): Bitmap? = iconCache.get(packageName)

    /** Loads (and caches) a row icon; the engine rasterises on its IO dispatcher. */
    suspend fun loadIcon(packageName: String, sizePx: Int): Bitmap? {
        iconCache.get(packageName)?.let { return it }
        if (packageName in missingIcons) return null
        val bitmap = inspector.loadIconBitmap(packageName, sizePx)
        if (bitmap == null) missingIcons += packageName else iconCache.put(packageName, bitmap)
        return bitmap
    }

    private fun updateQuery(transform: (AppListQuery) -> AppListQuery) {
        _uiState.update { it.copy(query = transform(it.query)).withVisible() }
    }

    private fun AppsUiState.withVisible(): AppsUiState =
        copy(visibleApps = AppListLogic.apply(apps, sizes, query, nameOrder))

    /** One entry per (kind, package); package-less duplicates collapse to one. */
    private fun dedupe(warnings: List<AppScanWarning>): List<AppScanWarning> =
        warnings.distinctBy { it.kind to it.packageName }

    override fun onCleared() {
        iconCache.evictAll()
    }

    private companion object {
        const val TAG = "AppsVM"
        const val MEASURE_CHUNK = 20
        const val ICON_CACHE_BYTES = 12 * 1024 * 1024
    }
}
