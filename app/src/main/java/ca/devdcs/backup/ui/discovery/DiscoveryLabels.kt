package ca.devdcs.backup.ui.discovery

import androidx.annotation.StringRes
import ca.devdcs.backup.R
import ca.devdcs.backup.core.discovery.apps.AppDataLocation
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.domain.model.AppComponent
import ca.devdcs.backup.domain.model.FileCategory

/*
 * Localized labels for the discovery UI's own vocabulary and for domain enums that have no
 * resource mapping yet (FileCategory, AppComponent, AppDataLocation). Engine enums that already
 * carry a mapper (PartitionLabels, FileScanText, AppKind.labelRes...) are used directly instead.
 */

/** The three tabs of the Discovery hub, in display order. */
enum class DiscoveryTab(@StringRes val labelRes: Int) {
    PARTITIONS(R.string.discovery_tab_partitions),
    APPS(R.string.discovery_tab_apps),
    FILES(R.string.discovery_tab_files),
}

@StringRes
fun FileCategory.labelRes(): Int = when (this) {
    FileCategory.IMAGES -> R.string.discovery_category_images
    FileCategory.VIDEOS -> R.string.discovery_category_videos
    FileCategory.AUDIO -> R.string.discovery_category_audio
    FileCategory.DOCUMENTS -> R.string.discovery_category_documents
    FileCategory.ARCHIVES -> R.string.discovery_category_archives
    FileCategory.BINARIES -> R.string.discovery_category_binaries
    FileCategory.DATABASES -> R.string.discovery_category_databases
    FileCategory.CONFIGS -> R.string.discovery_category_configs
}

@StringRes
fun AppComponent.labelRes(): Int = when (this) {
    AppComponent.APK -> R.string.discovery_component_apk
    AppComponent.INTERNAL_DATA -> R.string.discovery_component_internal_data
    AppComponent.EXTERNAL_DATA -> R.string.discovery_component_external_data
    AppComponent.OBB -> R.string.discovery_component_obb
    AppComponent.CACHE -> R.string.discovery_component_cache
}

@StringRes
fun AppDataLocation.labelRes(): Int = when (this) {
    AppDataLocation.CE_DATA -> R.string.discovery_location_ce_data
    AppDataLocation.DE_DATA -> R.string.discovery_location_de_data
    AppDataLocation.CE_CACHE -> R.string.discovery_location_cache
    AppDataLocation.EXTERNAL_DATA -> R.string.discovery_location_external_data
    AppDataLocation.MEDIA -> R.string.discovery_location_media
    AppDataLocation.OBB -> R.string.discovery_location_obb
}

@StringRes
fun ElevationTier.labelRes(): Int = when (this) {
    ElevationTier.NONE -> R.string.discovery_tier_standard
    ElevationTier.SHIZUKU -> R.string.discovery_tier_shizuku
    ElevationTier.ROOT -> R.string.discovery_tier_root
}

@StringRes
fun AppSort.labelRes(): Int = when (this) {
    AppSort.NAME -> R.string.discovery_apps_sort_name
    AppSort.SIZE -> R.string.discovery_apps_sort_size
    AppSort.UPDATED -> R.string.discovery_apps_sort_updated
}

@StringRes
fun AppListFilter.labelRes(): Int = when (this) {
    AppListFilter.USER -> R.string.discovery_apps_filter_user
    AppListFilter.SYSTEM -> R.string.discovery_apps_filter_system
    AppListFilter.UPDATED_SYSTEM -> R.string.discovery_apps_filter_updated
    AppListFilter.SPLIT -> R.string.discovery_apps_filter_split
    AppListFilter.HAS_OBB -> R.string.discovery_apps_filter_obb
}

@StringRes
fun SizeUnit.labelRes(): Int = when (this) {
    SizeUnit.B -> R.string.discovery_unit_b
    SizeUnit.KB -> R.string.discovery_unit_kb
    SizeUnit.MB -> R.string.discovery_unit_mb
    SizeUnit.GB -> R.string.discovery_unit_gb
    SizeUnit.TB -> R.string.discovery_unit_tb
}

@StringRes
fun FieldInputError.messageRes(): Int = when (this) {
    FieldInputError.INVALID_NUMBER -> R.string.discovery_files_error_number
    FieldInputError.TOO_LARGE -> R.string.discovery_files_error_too_large
    FieldInputError.DATES_REVERSED -> R.string.discovery_files_error_dates_reversed
}
