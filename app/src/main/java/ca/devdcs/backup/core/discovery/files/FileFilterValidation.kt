package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.domain.model.CompiledFileFilter
import ca.devdcs.backup.domain.model.FileFilter

/**
 * A reason a [FileFilter] cannot be used as written. Validation runs before any walk so a typo in
 * a regex is reported once, up front, instead of failing (or silently matching nothing) mid-scan.
 */
sealed interface FilterProblem {
    enum class RegexField { INCLUDE, EXCLUDE }
    enum class SizeField { MIN, MAX }

    data class InvalidRegex(val field: RegexField, val pattern: String, val message: String) : FilterProblem
    data class NegativeSize(val field: SizeField, val value: Long) : FilterProblem
    data class SizeRangeInverted(val minBytes: Long, val maxBytes: Long) : FilterProblem

    /** modifiedAfter >= modifiedBefore: both bounds are exclusive, so nothing can match. */
    data class DateRangeEmpty(val afterMillis: Long, val beforeMillis: Long) : FilterProblem

    /** The same extension is both included and excluded, so it can never match. */
    data class ExtensionConflict(val extensions: Set<String>) : FilterProblem
}

/**
 * Every problem with this filter, in field order; empty when the filter is usable. Pure, so UI
 * forms can call it on every keystroke.
 */
fun FileFilter.validate(): List<FilterProblem> {
    val problems = ArrayList<FilterProblem>()
    regexError(includeRegex)?.let { problems += FilterProblem.InvalidRegex(FilterProblem.RegexField.INCLUDE, includeRegex.orEmpty(), it) }
    regexError(excludeRegex)?.let { problems += FilterProblem.InvalidRegex(FilterProblem.RegexField.EXCLUDE, excludeRegex.orEmpty(), it) }
    val min = minSizeBytes
    val max = maxSizeBytes
    if (min != null && min < 0) problems += FilterProblem.NegativeSize(FilterProblem.SizeField.MIN, min)
    if (max != null && max < 0) problems += FilterProblem.NegativeSize(FilterProblem.SizeField.MAX, max)
    if (min != null && max != null && min >= 0 && max >= 0 && min > max) problems += FilterProblem.SizeRangeInverted(min, max)
    val after = modifiedAfterEpochMillis
    val before = modifiedBeforeEpochMillis
    if (after != null && before != null && after >= before) problems += FilterProblem.DateRangeEmpty(after, before)
    val included = includeExtensions.map(FileFilter::normalizeExtension).filter { it.isNotEmpty() }.toSet()
    val excluded = excludeExtensions.map(FileFilter::normalizeExtension).filter { it.isNotEmpty() }.toSet()
    val conflict = included intersect excluded
    if (conflict.isNotEmpty()) problems += FilterProblem.ExtensionConflict(conflict)
    return problems
}

/** Compiles the filter, or returns the validation problems instead of throwing. */
fun FileFilter.compileValidated(): Result<CompiledFileFilter> {
    val problems = validate()
    if (problems.isNotEmpty()) return Result.failure(InvalidFilterException(problems))
    return Result.success(compile())
}

/** Carries [problems] through [Result] for [compileValidated]. */
class InvalidFilterException(val problems: List<FilterProblem>) : IllegalArgumentException(
    problems.joinToString("; ") { it.toString() },
)

private fun regexError(pattern: String?): String? {
    if (pattern.isNullOrEmpty()) return null
    return try {
        Regex(pattern)
        null
    } catch (error: RuntimeException) {
        error.message ?: error.toString()
    }
}
