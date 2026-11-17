package ca.devdcs.backup.ui.profiles

import ca.devdcs.backup.domain.model.FileFilter
import ca.devdcs.backup.ui.discovery.FileFilterForm
import ca.devdcs.backup.ui.discovery.SizeUnit
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The reverse of [ca.devdcs.backup.ui.discovery.FileFilterFormParser]: turns a stored [FileFilter]
 * back into editor text so a profile's folder filter can be edited with the same form the
 * Discovery preview uses. Pure (the zone is a parameter) and unit-tested for the round trip.
 */
object FilterFormMapper {

    fun toForm(filter: FileFilter?, zone: ZoneId): FileFilterForm {
        if (filter == null) return FileFilterForm(useDefaultExclusions = false)
        val minUnit = filter.minSizeBytes?.let(SizeUnit::bestFor) ?: SizeUnit.MB
        val maxUnit = filter.maxSizeBytes?.let(SizeUnit::bestFor) ?: SizeUnit.MB
        return FileFilterForm(
            includeExtensions = filter.includeExtensions.joinToString(", "),
            excludeExtensions = filter.excludeExtensions.joinToString(", "),
            minSize = filter.minSizeBytes?.let { SizeUnit.format(it, minUnit) }.orEmpty(),
            minUnit = minUnit,
            maxSize = filter.maxSizeBytes?.let { SizeUnit.format(it, maxUnit) }.orEmpty(),
            maxUnit = maxUnit,
            modifiedOnOrAfterDay = filter.modifiedAfterEpochMillis?.let { afterBoundToDay(it, zone) },
            modifiedOnOrBeforeDay = filter.modifiedBeforeEpochMillis?.let { beforeBoundToDay(it, zone) },
            includeRegex = filter.includeRegex.orEmpty(),
            excludeRegex = filter.excludeRegex.orEmpty(),
            // Profiles store no "default exclusions" switch; the editor hides it.
            useDefaultExclusions = false,
        )
    }

    /** "After" bound (exclusive, one ms before day D starts) → UTC-midnight millis of D. */
    fun afterBoundToDay(boundMillis: Long, zone: ZoneId): Long = utcMidnight(localDate(boundMillis + 1, zone))

    /** "Before" bound (exclusive, start of day D + 1) → UTC-midnight millis of D. */
    fun beforeBoundToDay(boundMillis: Long, zone: ZoneId): Long = utcMidnight(localDate(boundMillis - 1, zone))

    private fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun utcMidnight(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

/**
 * The open folder-filter editor, in a form the SavedStateHandle can hold (the discovery form type
 * is not serializable). [location] identifies the edited folder target.
 */
@Serializable
data class FilterEditorSnapshot(
    val location: String,
    val includeExtensions: String = "",
    val excludeExtensions: String = "",
    val minSize: String = "",
    val minUnit: String = SizeUnit.MB.name,
    val maxSize: String = "",
    val maxUnit: String = SizeUnit.MB.name,
    val modifiedOnOrAfterDay: Long? = null,
    val modifiedOnOrBeforeDay: Long? = null,
    val includeRegex: String = "",
    val excludeRegex: String = "",
) {
    fun toForm(): FileFilterForm = FileFilterForm(
        includeExtensions = includeExtensions,
        excludeExtensions = excludeExtensions,
        minSize = minSize,
        minUnit = unitOf(minUnit),
        maxSize = maxSize,
        maxUnit = unitOf(maxUnit),
        modifiedOnOrAfterDay = modifiedOnOrAfterDay,
        modifiedOnOrBeforeDay = modifiedOnOrBeforeDay,
        includeRegex = includeRegex,
        excludeRegex = excludeRegex,
        useDefaultExclusions = false,
    )

    companion object {
        fun of(location: String, form: FileFilterForm) = FilterEditorSnapshot(
            location = location,
            includeExtensions = form.includeExtensions,
            excludeExtensions = form.excludeExtensions,
            minSize = form.minSize,
            minUnit = form.minUnit.name,
            maxSize = form.maxSize,
            maxUnit = form.maxUnit.name,
            modifiedOnOrAfterDay = form.modifiedOnOrAfterDay,
            modifiedOnOrBeforeDay = form.modifiedOnOrBeforeDay,
            includeRegex = form.includeRegex,
            excludeRegex = form.excludeRegex,
        )

        private fun unitOf(name: String): SizeUnit = SizeUnit.entries.firstOrNull { it.name == name } ?: SizeUnit.MB
    }
}
