package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.domain.model.FileFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileFilterValidationTest {

    @Test
    fun `valid filter has no problems and compiles`() {
        val filter = FileFilter(includeExtensions = setOf("jpg"), minSizeBytes = 1, maxSizeBytes = 10, includeRegex = "^DCIM/")
        assertTrue(filter.validate().isEmpty())
        assertTrue(filter.compileValidated().isSuccess)
    }

    @Test
    fun `reports every problem up front`() {
        val filter = FileFilter(
            includeExtensions = setOf(".JPG", "png"),
            excludeExtensions = setOf("jpg"),
            minSizeBytes = 10,
            maxSizeBytes = 5,
            modifiedAfterEpochMillis = 100,
            modifiedBeforeEpochMillis = 100,
            includeRegex = "([",
            excludeRegex = "ok",
        )
        val problems = filter.validate()
        assertEquals(4, problems.size)
        val regex = problems[0] as FilterProblem.InvalidRegex
        assertEquals(FilterProblem.RegexField.INCLUDE, regex.field)
        assertEquals("([", regex.pattern)
        assertTrue(problems[1] is FilterProblem.SizeRangeInverted)
        assertTrue(problems[2] is FilterProblem.DateRangeEmpty)
        assertEquals(FilterProblem.ExtensionConflict(setOf("jpg")), problems[3])
        val failure = filter.compileValidated().exceptionOrNull() as InvalidFilterException
        assertEquals(problems, failure.problems)
    }

    @Test
    fun `negative sizes are reported without an inverted range`() {
        val problems = FileFilter(minSizeBytes = -1, maxSizeBytes = -2, excludeRegex = "*").validate()
        assertEquals(
            listOf(FilterProblem.RegexField.EXCLUDE),
            problems.filterIsInstance<FilterProblem.InvalidRegex>().map { it.field },
        )
        assertEquals(2, problems.filterIsInstance<FilterProblem.NegativeSize>().size)
        assertTrue(problems.none { it is FilterProblem.SizeRangeInverted })
    }
}
