package ca.devdcs.backup.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The full "what to back up" answer for one profile. Kept as a single JSON column so a profile is
 * self-contained and portable (export/import) without joins across several target tables.
 */
@Serializable
data class TargetSelection(
    val partitions: List<PartitionTarget> = emptyList(),
    val apps: List<AppTarget> = emptyList(),
    val paths: List<PathTarget> = emptyList(),
    val categories: Set<FileCategory> = emptySet(),
) {
    val isEmpty: Boolean get() = partitions.isEmpty() && apps.isEmpty() && paths.isEmpty() && categories.isEmpty()

    /** True when any target needs root or a privileged shell (partitions, app private data, OBB). */
    val requiresElevation: Boolean
        get() = partitions.isNotEmpty() || apps.any { target -> target.components.any(AppComponent::requiresElevation) }

    companion object {
        val EMPTY = TargetSelection()
    }
}

/**
 * Which A/B slot(s) of a partition to image. Non-A/B devices use [NONE]; [BOTH] exists for
 * disaster-recovery images where the user wants the inactive slot preserved as well.
 */
@Serializable
enum class SlotPolicy {
    @SerialName("active") ACTIVE,
    @SerialName("inactive") INACTIVE,
    @SerialName("both") BOTH,
    @SerialName("none") NONE,
}

/**
 * One partition by logical name (boot, vendor_boot, persist...). [blockDevicePath] is the resolved
 * `/dev/block/by-name/...` node captured when the user picked it; null means resolve at run time.
 */
@Serializable
data class PartitionTarget(
    val name: String,
    val blockDevicePath: String? = null,
    val slotPolicy: SlotPolicy = SlotPolicy.ACTIVE,
    /**
     * Which node family the user picked when a name exists both as a physical by-name partition and
     * as a device-mapper node (metadata-encrypted `userdata`, retrofit dynamic `system`): true =
     * mapper (dm) node, false = physical by-name node, null = unknown (older profiles; physical is
     * preferred). Imaging the wrong one of the pair corrupts data on restore, so the resolver never
     * crosses families when this is set.
     */
    val logical: Boolean? = null,
)

/** Pieces of an installed app that can be captured independently. */
@Serializable
enum class AppComponent(val requiresElevation: Boolean) {
    @SerialName("apk") APK(requiresElevation = false),
    @SerialName("internal_data") INTERNAL_DATA(requiresElevation = true),
    @SerialName("external_data") EXTERNAL_DATA(requiresElevation = false),
    @SerialName("obb") OBB(requiresElevation = false),
    @SerialName("cache") CACHE(requiresElevation = true),
}

@Serializable
data class AppTarget(
    val packageName: String,
    val components: Set<AppComponent> = setOf(AppComponent.APK, AppComponent.INTERNAL_DATA),
)

/**
 * A directory or file to back up. [location] is either an absolute filesystem path or a SAF tree /
 * document URI; the engine picks the access strategy from the scheme.
 */
@Serializable
data class PathTarget(
    val location: String,
    val recursive: Boolean = true,
    val filter: FileFilter? = null,
) {
    val isSafUri: Boolean get() = location.startsWith("content://")
}

/**
 * Broad file families used by media/document scans and by [StructureMode.CATEGORIZED] layouts.
 * [defaultExtensions] is the classification the engines apply when the user has no custom filter.
 */
@Serializable
enum class FileCategory(val defaultExtensions: Set<String>) {
    @SerialName("images") IMAGES(setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg", "raw", "dng", "tiff", "tif", "avif")),
    @SerialName("videos") VIDEOS(setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "m4v", "ts", "mts", "wmv", "flv")),
    @SerialName("audio") AUDIO(setOf("mp3", "flac", "aac", "m4a", "ogg", "opus", "wav", "wma", "amr", "mid", "aiff")),
    @SerialName("documents") DOCUMENTS(setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "txt", "md", "rtf", "epub", "csv")),
    @SerialName("archives") ARCHIVES(setOf("zip", "7z", "rar", "tar", "gz", "xz", "zst", "bz2", "tgz", "lz4")),
    @SerialName("binaries") BINARIES(setOf("apk", "apks", "xapk", "apkm", "aab", "img", "bin", "so", "dex", "jar", "exe")),
    @SerialName("databases") DATABASES(setOf("db", "sqlite", "sqlite3", "db-wal", "db-shm", "realm", "mdb")),
    @SerialName("configs") CONFIGS(setOf("xml", "json", "yaml", "yml", "toml", "ini", "conf", "cfg", "prop", "properties", "plist"));

    /** Case-insensitive membership test on a bare extension ("JPG" or ".jpg" both match). */
    fun matchesExtension(extension: String): Boolean = extension.removePrefix(".").lowercase() in defaultExtensions

    companion object {
        /** First category whose default extensions include [extension], or null when unclassified. */
        fun classify(extension: String): FileCategory? = entries.firstOrNull { it.matchesExtension(extension) }
    }
}
