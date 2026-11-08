package ca.devdcs.backup.ui.settings

import ca.devdcs.backup.core.preferences.AppPreferences
import ca.devdcs.backup.domain.model.ArchiveFormat
import ca.devdcs.backup.domain.model.ChecksumAlgorithm

/**
 * The subset of PipelineOptions a new backup profile inherits from AppPreferences. Kept as its own
 * value so the combination rules below can be unit-tested without DataStore.
 */
data class PipelineDefaults(
    val archiveFormat: ArchiveFormat,
    val compressionLevel: Int,
    val checksumAlgorithm: ChecksumAlgorithm,
    val destinationId: String?,
) {
    companion object {
        fun from(prefs: AppPreferences) = PipelineDefaults(
            archiveFormat = prefs.defaultArchiveFormat,
            compressionLevel = prefs.defaultCompressionLevel,
            checksumAlgorithm = prefs.defaultChecksumAlgorithm,
            destinationId = prefs.defaultDestinationId,
        )
    }
}

/** A stored combination that a profile created from it could not use as-is. */
enum class DefaultsIssue {
    /** RAW_COPY / DD_IMAGE are per-target transfer methods, not a profile-wide default container. */
    FORMAT_NOT_SELECTABLE,
    /** The compression level is outside what the selected format accepts. */
    LEVEL_OUT_OF_RANGE,
    /** The default destination was deleted after it was chosen. */
    DESTINATION_MISSING,
}

/**
 * Valid-combination rules for the defaults screen. They exist so every write keeps the stored
 * preferences coherent (a zstd level of 19 never survives a switch to gzip's 0..9 scale), while a
 * level that a format ignores (plain tar) is kept untouched so switching back restores it.
 */
object PipelineDefaultsRules {

    /** Upper bound AppPreferencesRepository accepts for any stored level. */
    const val STORED_LEVEL_MAX = 19

    /** Formats offered as a default: real archive containers only, in declaration order. */
    val selectableFormats: List<ArchiveFormat> = ArchiveFormat.entries.filter { it.isArchive }

    fun isSelectable(format: ArchiveFormat): Boolean = format.isArchive

    /** True when the format has a compression level the user can tune. */
    fun levelApplies(format: ArchiveFormat): Boolean = format.supportsCompressionLevel && format.maxCompressionLevel > 0

    /** Accepted levels for [format]; `0..0` when the format has no level. */
    fun levelRange(format: ArchiveFormat): IntRange = if (levelApplies(format)) 0..format.maxCompressionLevel else 0..0

    /**
     * Switches the format, clamping the level into the new range. Non-selectable formats are
     * refused (the current value is returned unchanged).
     */
    fun withFormat(current: PipelineDefaults, format: ArchiveFormat): PipelineDefaults {
        if (!isSelectable(format)) return current
        val level = if (levelApplies(format)) {
            current.compressionLevel.coerceIn(levelRange(format))
        } else {
            current.compressionLevel.coerceIn(0, STORED_LEVEL_MAX)
        }
        return current.copy(archiveFormat = format, compressionLevel = level)
    }

    /** Sets the level within the current format's range; ignored when the format has no level. */
    fun withLevel(current: PipelineDefaults, level: Int): PipelineDefaults {
        if (!levelApplies(current.archiveFormat)) return current
        return current.copy(compressionLevel = level.coerceIn(levelRange(current.archiveFormat)))
    }

    /**
     * Everything wrong with [defaults]. [destinationIds] is null while the destination list has not
     * loaded yet, in which case the destination is not judged (never report a false "missing").
     */
    fun issues(defaults: PipelineDefaults, destinationIds: Set<String>?): Set<DefaultsIssue> = buildSet {
        if (!isSelectable(defaults.archiveFormat)) add(DefaultsIssue.FORMAT_NOT_SELECTABLE)
        if (levelApplies(defaults.archiveFormat) && defaults.compressionLevel !in levelRange(defaults.archiveFormat)) {
            add(DefaultsIssue.LEVEL_OUT_OF_RANGE)
        }
        val destinationId = defaults.destinationId
        if (destinationId != null && destinationIds != null && destinationId !in destinationIds) {
            add(DefaultsIssue.DESTINATION_MISSING)
        }
    }

    /** The closest valid combination: default format, clamped level, missing destination cleared. */
    fun normalize(defaults: PipelineDefaults, destinationIds: Set<String>?): PipelineDefaults {
        val format = if (isSelectable(defaults.archiveFormat)) defaults.archiveFormat else ArchiveFormat.DEFAULT
        val formatted = withFormat(defaults.copy(archiveFormat = format), format)
        val destination = defaults.destinationId?.takeUnless { destinationIds != null && it !in destinationIds }
        return formatted.copy(destinationId = destination)
    }

    /** 7z builds the whole archive in local staging before upload, so free local space must cover it. */
    fun needsFullLocalStaging(format: ArchiveFormat): Boolean = format == ArchiveFormat.SEVEN_ZIP
}
