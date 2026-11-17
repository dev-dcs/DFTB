package ca.devdcs.backup.ui.settings

import ca.devdcs.backup.ui.navigation.DftbDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionKeyTest {

    @Test
    fun keysAreUniqueLowercaseAndStable() {
        val keys = SettingsSectionKey.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { assertEquals(it, it.lowercase()) }
        // Persisted in back stacks: renaming any of these would break restored navigation.
        assertEquals(
            listOf("language", "appearance", "mode", "defaults", "security", "notifications", "storage", "about"),
            keys,
        )
    }

    @Test
    fun fromKeyRoundTripsEveryEntry() {
        SettingsSectionKey.entries.forEach { assertEquals(it, SettingsSectionKey.fromKey(it.key)) }
    }

    @Test
    fun fromKeyToleratesCaseAndWhitespace() {
        assertEquals(SettingsSectionKey.MODE, SettingsSectionKey.fromKey(" Mode "))
        assertEquals(SettingsSectionKey.DEFAULTS, SettingsSectionKey.fromKey("DEFAULTS"))
    }

    @Test
    fun unknownOrBlankKeysResolveToNull() {
        assertNull(SettingsSectionKey.fromKey(null))
        assertNull(SettingsSectionKey.fromKey(""))
        assertNull(SettingsSectionKey.fromKey("   "))
        assertNull(SettingsSectionKey.fromKey("backup"))
        assertNull(SettingsSectionKey.fromKey("mode/extra"))
    }

    @Test
    fun destinationCarriesTheKey() {
        assertEquals(DftbDestination.SettingsSection("security"), SettingsSectionKey.SECURITY.destination)
    }

    @Test
    fun groupingCoversEverySectionOnceInCategoryOrder() {
        val grouped = SettingsSectionKey.grouped()
        assertEquals(SettingsCategory.entries.filter { c -> SettingsSectionKey.entries.any { it.category == c } }, grouped.map { it.first })
        assertEquals(SettingsSectionKey.entries.toSet(), grouped.flatMap { it.second }.toSet())
        assertEquals(SettingsSectionKey.entries.size, grouped.sumOf { it.second.size })
        grouped.forEach { (category, sections) -> assertTrue(sections.all { it.category == category }) }
    }
}
