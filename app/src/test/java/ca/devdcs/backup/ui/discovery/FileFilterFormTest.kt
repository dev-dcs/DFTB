package ca.devdcs.backup.ui.discovery

import ca.devdcs.backup.core.discovery.files.FilterProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class FileFilterFormTest {

    private val zone: ZoneId = ZoneId.of("America/Toronto")

    private fun day(y: Int, m: Int, d: Int): Long = LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun emptyFormIsAValidEmptyFilter() {
        val parsed = FileFilterFormParser.parse(FileFilterForm(), zone)
        assertTrue(parsed.isValid)
        assertTrue(parsed.filter.isEmpty)
    }

    @Test
    fun extensionsAreSplitAndNormalised() {
        assertEquals(setOf("jpg", "png", "heic", "raw"), FileFilterFormParser.parseExtensions(" jpg, .PNG *.heic;raw ,, "))
        assertTrue(FileFilterFormParser.parseExtensions("  ").isEmpty())
    }

    @Test
    fun sizeUnitsAreDecimal() {
        assertEquals(1_500_000L, SizeUnit.MB.toBytes(BigDecimal("1.5")))
        assertEquals(2L * 1_000_000_000_000L, SizeUnit.TB.toBytes(BigDecimal("2")))
        assertEquals(2L, SizeUnit.B.toBytes(BigDecimal("1.5")))
        assertNull(SizeUnit.TB.toBytes(BigDecimal("99999999")))
    }

    @Test
    fun bestUnitRoundTrips() {
        assertEquals(SizeUnit.GB, SizeUnit.bestFor(5_000_000_000L))
        assertEquals(SizeUnit.KB, SizeUnit.bestFor(2_000L))
        // Only exact representations: 1500 B is not a whole number of kB.
        assertEquals(SizeUnit.B, SizeUnit.bestFor(1_500L))
        assertEquals(SizeUnit.B, SizeUnit.bestFor(0L))
        assertEquals("1.5", SizeUnit.format(1_500L, SizeUnit.KB))
        assertEquals("5", SizeUnit.format(5_000_000_000L, SizeUnit.GB))
    }

    @Test
    fun sizeTextAcceptsCommaDecimalAndRejectsGarbage() {
        val parsed = FileFilterFormParser.parse(FileFilterForm(minSize = "1,5", minUnit = SizeUnit.KB, maxSize = "abc"), zone)
        assertEquals(1_500L, parsed.filter.minSizeBytes)
        assertNull(parsed.filter.maxSizeBytes)
        assertEquals(FieldInputError.INVALID_NUMBER, parsed.inputErrors[FilterFormField.MAX_SIZE])
        assertFalse(parsed.isValid)
    }

    @Test
    fun overflowIsReportedAsTooLarge() {
        val parsed = FileFilterFormParser.parse(FileFilterForm(maxSize = "1e30", maxUnit = SizeUnit.TB), zone)
        assertEquals(FieldInputError.TOO_LARGE, parsed.inputErrors[FilterFormField.MAX_SIZE])
    }

    @Test
    fun invertedSizeRangeIsAnEngineProblemUnderMax() {
        val parsed = FileFilterFormParser.parse(FileFilterForm(minSize = "2", maxSize = "1", minUnit = SizeUnit.GB, maxUnit = SizeUnit.GB), zone)
        assertTrue(parsed.inputErrors.isEmpty())
        val problems = parsed.problemsFor(FilterFormField.MAX_SIZE)
        assertEquals(1, problems.size)
        assertTrue(problems.single() is FilterProblem.SizeRangeInverted)
    }

    @Test
    fun negativeSizeIsReportedUnderItsField() {
        val parsed = FileFilterFormParser.parse(FileFilterForm(minSize = "-1"), zone)
        assertTrue(parsed.problemsFor(FilterFormField.MIN_SIZE).single() is FilterProblem.NegativeSize)
    }

    @Test
    fun invalidRegexIsLiveValidated() {
        val parsed = FileFilterFormParser.parse(FileFilterForm(includeRegex = "([a-z", excludeRegex = "\\.tmp$"), zone)
        assertEquals(1, parsed.problemsFor(FilterFormField.INCLUDE_REGEX).size)
        assertTrue(parsed.problemsFor(FilterFormField.EXCLUDE_REGEX).isEmpty())
        assertEquals("\\.tmp$", parsed.filter.excludeRegex)
    }

    @Test
    fun extensionConflictGoesUnderExclude() {
        val parsed = FileFilterFormParser.parse(FileFilterForm(includeExtensions = "jpg png", excludeExtensions = ".JPG"), zone)
        assertTrue(parsed.problemsFor(FilterFormField.EXCLUDE_EXTENSIONS).single() is FilterProblem.ExtensionConflict)
    }

    @Test
    fun dateBoundsAreInclusiveLocalDays() {
        val oct1 = day(2026, 10, 1)
        val startLocal = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextLocal = LocalDate.of(2026, 10, 2).atStartOfDay(zone).toInstant().toEpochMilli()
        val parsed = FileFilterFormParser.parse(FileFilterForm(modifiedOnOrAfterDay = oct1, modifiedOnOrBeforeDay = oct1), zone)
        assertTrue(parsed.isValid)
        assertEquals(startLocal - 1, parsed.filter.modifiedAfterEpochMillis)
        assertEquals(nextLocal, parsed.filter.modifiedBeforeEpochMillis)
        val compiled = parsed.filter.compile()
        assertTrue(compiled.matches("a.jpg", 1, startLocal))
        assertTrue(compiled.matches("a.jpg", 1, nextLocal - 1))
        assertFalse(compiled.matches("a.jpg", 1, nextLocal))
        assertFalse(compiled.matches("a.jpg", 1, startLocal - 1))
    }

    @Test
    fun adjacentReversedDatesAreCaughtByTheForm() {
        // after = Oct 6 start - 1 ms, before = Oct 5 start + 1 day: the engine sees a non-empty range.
        val parsed = FileFilterFormParser.parse(
            FileFilterForm(modifiedOnOrAfterDay = day(2026, 10, 5), modifiedOnOrBeforeDay = day(2026, 10, 4)),
            zone,
        )
        assertEquals(FieldInputError.DATES_REVERSED, parsed.inputErrors[FilterFormField.MODIFIED_BEFORE])
        assertFalse(parsed.isValid)
    }

    @Test
    fun widelyReversedDatesAreAlsoAnEngineProblemUnderBefore() {
        val parsed = FileFilterFormParser.parse(
            FileFilterForm(modifiedOnOrAfterDay = day(2026, 10, 9), modifiedOnOrBeforeDay = day(2026, 10, 1)),
            zone,
        )
        assertEquals(FieldInputError.DATES_REVERSED, parsed.inputErrors[FilterFormField.MODIFIED_BEFORE])
        assertTrue(parsed.problemsFor(FilterFormField.MODIFIED_BEFORE).single() is FilterProblem.DateRangeEmpty)
    }
}
