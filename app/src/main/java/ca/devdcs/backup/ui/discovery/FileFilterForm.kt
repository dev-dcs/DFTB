package ca.devdcs.backup.ui.discovery

import ca.devdcs.backup.core.discovery.files.FilterProblem
import ca.devdcs.backup.core.discovery.files.validate
import ca.devdcs.backup.domain.model.FileFilter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Size units of the filter editor. Decimal (SI) multiples on purpose: Android's own
 * Formatter.formatShortFileSize, which the preview uses for every size, is SI since API 26, so
 * "100 MB" typed here means the same thing as "100 MB" shown in the results.
 */
enum class SizeUnit(val bytes: Long) {
    B(1L),
    KB(1_000L),
    MB(1_000_000L),
    GB(1_000_000_000L),
    TB(1_000_000_000_000L),
    ;

    /** [amount] of this unit in whole bytes (rounded half-up), or null when it overflows a Long. */
    fun toBytes(amount: BigDecimal): Long? = try {
        amount.multiply(BigDecimal.valueOf(bytes)).setScale(0, RoundingMode.HALF_UP).longValueExact()
    } catch (e: ArithmeticException) {
        null
    }

    companion object {
        /** Largest unit that represents [bytes] exactly, so an existing filter re-opens as "5 GB" not "5000000000 B". */
        fun bestFor(bytes: Long): SizeUnit {
            if (bytes == 0L) return B
            return entries.lastOrNull { bytes % it.bytes == 0L } ?: B
        }

        /** [bytes] expressed in [unit] without trailing zeros ("1.5", "20"). */
        fun format(bytes: Long, unit: SizeUnit): String =
            BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(unit.bytes)).stripTrailingZeros().toPlainString()
    }
}

/** The editor's raw inputs. Text stays text so a half-typed "1." is not reformatted under the cursor. */
data class FileFilterForm(
    /** Free text; any of `, ; space` separate entries; dots and `*.` prefixes are tolerated. */
    val includeExtensions: String = "",
    val excludeExtensions: String = "",
    val minSize: String = "",
    val minUnit: SizeUnit = SizeUnit.MB,
    val maxSize: String = "",
    val maxUnit: SizeUnit = SizeUnit.MB,
    /** UTC-midnight millis of the picked calendar day (Material DatePicker convention); inclusive. */
    val modifiedOnOrAfterDay: Long? = null,
    /** UTC-midnight millis of the picked calendar day; inclusive. */
    val modifiedOnOrBeforeDay: Long? = null,
    val includeRegex: String = "",
    val excludeRegex: String = "",
    /** Mirrors ScanOptions.useDefaultExclusions (not part of [FileFilter]). */
    val useDefaultExclusions: Boolean = true,
)

/** Editor fields, used to place inline errors next to the input that caused them. */
enum class FilterFormField {
    INCLUDE_EXTENSIONS,
    EXCLUDE_EXTENSIONS,
    MIN_SIZE,
    MAX_SIZE,
    MODIFIED_AFTER,
    MODIFIED_BEFORE,
    INCLUDE_REGEX,
    EXCLUDE_REGEX,
}

/** Input that could not even be turned into a [FileFilter] value. */
enum class FieldInputError {
    /** Not a number. */
    INVALID_NUMBER,

    /** Number too large to express in bytes. */
    TOO_LARGE,

    /**
     * "On or before" day is earlier than the "on or after" day. Checked here because the engine's
     * exclusive bounds for adjacent days (after = D+1 start - 1 ms, before = D+1 start) look non-empty
     * to [FileFilter.validate] yet can match nothing.
     */
    DATES_REVERSED,
}

/**
 * Result of [FileFilterFormParser.parse]: the filter built from the parsable fields, the fields
 * that could not be parsed at all, and the engine's own [FileFilter.validate] problems.
 */
data class ParsedFilterForm(
    val filter: FileFilter,
    val inputErrors: Map<FilterFormField, FieldInputError>,
    val problems: List<FilterProblem>,
) {
    val isValid: Boolean get() = inputErrors.isEmpty() && problems.isEmpty()

    /** Engine problems to show under [field]. */
    fun problemsFor(field: FilterFormField): List<FilterProblem> =
        problems.filter { FileFilterFormParser.fieldOf(it) == field }
}

/**
 * Turns [FileFilterForm] text into a [FileFilter] and runs the engine's validation on it. Pure (the
 * time zone is a parameter) so it can run on every keystroke and be unit-tested.
 */
object FileFilterFormParser {

    private val SEPARATORS = Regex("[,;\\s]+")

    /** Parsed size text: blank, a decimal amount, or garbage. */
    sealed interface SizeText {
        data object Blank : SizeText
        data class Amount(val value: BigDecimal) : SizeText
        data object Invalid : SizeText
    }

    fun parse(form: FileFilterForm, zone: ZoneId): ParsedFilterForm {
        val errors = LinkedHashMap<FilterFormField, FieldInputError>()
        val min = sizeBytes(form.minSize, form.minUnit, FilterFormField.MIN_SIZE, errors)
        val max = sizeBytes(form.maxSize, form.maxUnit, FilterFormField.MAX_SIZE, errors)
        val afterDay = form.modifiedOnOrAfterDay
        val beforeDay = form.modifiedOnOrBeforeDay
        if (afterDay != null && beforeDay != null && localDate(afterDay).isAfter(localDate(beforeDay))) {
            errors[FilterFormField.MODIFIED_BEFORE] = FieldInputError.DATES_REVERSED
        }
        val filter = FileFilter(
            includeExtensions = parseExtensions(form.includeExtensions),
            excludeExtensions = parseExtensions(form.excludeExtensions),
            minSizeBytes = min,
            maxSizeBytes = max,
            modifiedAfterEpochMillis = form.modifiedOnOrAfterDay?.let { modifiedAfterBound(it, zone) },
            modifiedBeforeEpochMillis = form.modifiedOnOrBeforeDay?.let { modifiedBeforeBound(it, zone) },
            includeRegex = form.includeRegex.takeIf { it.isNotEmpty() },
            excludeRegex = form.excludeRegex.takeIf { it.isNotEmpty() },
        )
        return ParsedFilterForm(filter, errors, filter.validate())
    }

    /** "jpg, .PNG *.heic;raw" -> {jpg, png, heic, raw}. */
    fun parseExtensions(text: String): Set<String> =
        text.split(SEPARATORS)
            .map { FileFilter.normalizeExtension(it.trim().removePrefix("*")) }
            .filter { it.isNotEmpty() }
            .toCollection(LinkedHashSet())

    /** Accepts "1.5" and "1,5" (comma as decimal mark when there is no dot). */
    fun parseSizeText(text: String): SizeText {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return SizeText.Blank
        val normalized = if ('.' !in trimmed && trimmed.count { it == ',' } == 1) trimmed.replace(',', '.') else trimmed
        return try {
            SizeText.Amount(BigDecimal(normalized))
        } catch (e: NumberFormatException) {
            SizeText.Invalid
        }
    }

    /** Start of the calendar day [dayUtcMillis] (a DatePicker value) in [zone], as epoch millis. */
    fun dayStartMillis(dayUtcMillis: Long, zone: ZoneId): Long =
        localDate(dayUtcMillis).atStartOfDay(zone).toInstant().toEpochMilli()

    /** FileFilter's "after" is exclusive, so "on or after day D" is one millisecond before D starts. */
    fun modifiedAfterBound(dayUtcMillis: Long, zone: ZoneId): Long = dayStartMillis(dayUtcMillis, zone) - 1

    /** FileFilter's "before" is exclusive, so "on or before day D" is the start of D + 1. */
    fun modifiedBeforeBound(dayUtcMillis: Long, zone: ZoneId): Long =
        localDate(dayUtcMillis).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Which field an engine problem belongs under. */
    fun fieldOf(problem: FilterProblem): FilterFormField = when (problem) {
        is FilterProblem.InvalidRegex ->
            if (problem.field == FilterProblem.RegexField.INCLUDE) FilterFormField.INCLUDE_REGEX else FilterFormField.EXCLUDE_REGEX
        is FilterProblem.NegativeSize ->
            if (problem.field == FilterProblem.SizeField.MIN) FilterFormField.MIN_SIZE else FilterFormField.MAX_SIZE
        is FilterProblem.SizeRangeInverted -> FilterFormField.MAX_SIZE
        is FilterProblem.DateRangeEmpty -> FilterFormField.MODIFIED_BEFORE
        is FilterProblem.ExtensionConflict -> FilterFormField.EXCLUDE_EXTENSIONS
    }

    private fun localDate(dayUtcMillis: Long): LocalDate =
        Instant.ofEpochMilli(dayUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()

    private fun sizeBytes(
        text: String,
        unit: SizeUnit,
        field: FilterFormField,
        errors: MutableMap<FilterFormField, FieldInputError>,
    ): Long? = when (val parsed = parseSizeText(text)) {
        SizeText.Blank -> null
        SizeText.Invalid -> {
            errors[field] = FieldInputError.INVALID_NUMBER
            null
        }
        is SizeText.Amount -> unit.toBytes(parsed.value) ?: run {
            errors[field] = FieldInputError.TOO_LARGE
            null
        }
    }
}
