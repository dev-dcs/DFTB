package ca.devdcs.backup.domain

import ca.devdcs.backup.domain.model.AppComponent
import ca.devdcs.backup.domain.model.AppTarget
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.FileFilter
import ca.devdcs.backup.domain.model.PartitionTarget
import ca.devdcs.backup.domain.model.PathTarget
import ca.devdcs.backup.domain.model.SlotPolicy
import ca.devdcs.backup.domain.model.TargetSelection
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetSelectionSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    private val full = TargetSelection(
        partitions = listOf(
            PartitionTarget("boot", "/dev/block/by-name/boot_a", SlotPolicy.BOTH),
            PartitionTarget("persist", null, SlotPolicy.NONE),
        ),
        apps = listOf(
            AppTarget("org.example.one", setOf(AppComponent.APK, AppComponent.INTERNAL_DATA, AppComponent.OBB)),
            AppTarget("org.example.two", emptySet()),
        ),
        paths = listOf(
            PathTarget("/sdcard/DCIM", recursive = true, filter = FileFilter(includeExtensions = setOf("jpg", "mp4"), minSizeBytes = 1024)),
            PathTarget("content://com.android.externalstorage.documents/tree/primary%3ADocuments", recursive = false),
        ),
        categories = setOf(FileCategory.IMAGES, FileCategory.DOCUMENTS),
    )

    @Test
    fun `empty selection round trips and reports empty`() {
        val restored = json.decodeFromString<TargetSelection>(json.encodeToString(TargetSelection.EMPTY))
        assertEquals(TargetSelection.EMPTY, restored)
        assertTrue(restored.isEmpty)
        assertFalse(restored.requiresElevation)
    }

    @Test
    fun `full selection round trips`() {
        val restored = json.decodeFromString<TargetSelection>(json.encodeToString(full))
        assertEquals(full, restored)
        assertFalse(restored.isEmpty)
        assertTrue(restored.requiresElevation)
    }

    @Test
    fun `nested enums use snake_case serial names`() {
        val encoded = json.encodeToString(full)
        assertTrue(encoded.contains("\"internal_data\""))
        assertTrue(encoded.contains("\"both\""))
        assertTrue(encoded.contains("\"images\""))
        assertFalse(encoded.contains("INTERNAL_DATA"))
    }

    @Test
    fun `saf uri detection`() {
        assertTrue(full.paths[1].isSafUri)
        assertFalse(full.paths[0].isSafUri)
    }

    @Test
    fun `elevation is required only for privileged components`() {
        val apkOnly = TargetSelection(apps = listOf(AppTarget("a", setOf(AppComponent.APK, AppComponent.EXTERNAL_DATA))))
        assertFalse(apkOnly.requiresElevation)
        val withCache = TargetSelection(apps = listOf(AppTarget("a", setOf(AppComponent.CACHE))))
        assertTrue(withCache.requiresElevation)
        val partitionOnly = TargetSelection(partitions = listOf(PartitionTarget("boot")))
        assertTrue(partitionOnly.requiresElevation)
    }

    @Test
    fun `file category classification is case and dot insensitive`() {
        assertEquals(FileCategory.IMAGES, FileCategory.classify(".JPG"))
        assertEquals(FileCategory.ARCHIVES, FileCategory.classify("zst"))
        assertEquals(FileCategory.DATABASES, FileCategory.classify("db-wal"))
        assertNull(FileCategory.classify("unknownext"))
    }
}
