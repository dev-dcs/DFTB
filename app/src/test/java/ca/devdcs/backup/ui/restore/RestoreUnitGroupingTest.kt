package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.restore.engine.RestoreUnitKind
import ca.devdcs.backup.ui.restore.RestoreTestData.app
import ca.devdcs.backup.ui.restore.RestoreTestData.artifact
import ca.devdcs.backup.ui.restore.RestoreTestData.files
import ca.devdcs.backup.ui.restore.RestoreTestData.item
import ca.devdcs.backup.ui.restore.RestoreTestData.nandroid
import ca.devdcs.backup.ui.restore.RestoreTestData.partition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreUnitGroupingTest {

    @Test
    fun `one unit per source id, routed by manifest kind`() {
        val units = RestoreUnitGrouping.units(
            listOf(app("org.zebra"), partition("boot_a"), files("1", "DCIM", 5, 7), nandroid("2026-10-01")),
        )
        assertEquals(4, units.size)
        assertEquals(RestoreUnitKind.APP, units.first { it.id == "apps:org.zebra" }.kind)
        assertEquals(RestoreUnitKind.PARTITION, units.first { it.id == "partitions:boot_a" }.kind)
        assertEquals(RestoreUnitKind.FILE_TREE, units.first { it.id == "files:1" }.kind)
        val twrp = units.first { it.id == "nandroid:2026-10-01" }
        assertEquals(RestoreUnitKind.FILE_TREE, twrp.kind)
        assertTrue(twrp.nandroid)
        assertFalse(units.first { it.id == "files:1" }.nandroid)
    }

    @Test
    fun `artifacts of the same source merge into one unit`() {
        val a = artifact("files:x", "files", "Docs", listOf(item("files/a", 3)), stored = 10)
        val b = artifact("files:x", "files", "Docs", listOf(item("files/b", 4)), stored = 20)
        val unit = RestoreUnitGrouping.units(listOf(a, b)).single()
        assertEquals(2, unit.artifactCount)
        assertEquals(30L, unit.storedBytes)
        assertEquals(7L, unit.restoreBytes)
    }

    @Test
    fun `app units expose the package from the app manifest member`() {
        val unit = RestoreUnitGrouping.units(listOf(app("com.example.mail"))).single()
        assertEquals("com.example.mail", unit.packageName)
        assertEquals("com.example.mail", unit.displayName)
        assertEquals(51L, unit.restoreBytes)
    }

    @Test
    fun `partition units carry node, base name, family and identity risk`() {
        val units = RestoreUnitGrouping.units(listOf(partition("boot_b", 8192), partition("persist"), partition("system_a", logical = true)))
        val boot = units.first { it.id == "partitions:boot_b" }
        assertEquals("boot_b", boot.partitionNode)
        assertEquals("boot", boot.partitionBase)
        assertFalse(boot.criticalIdentity)
        assertEquals(8192L, boot.restoreBytes)
        assertTrue(units.first { it.id == "partitions:persist" }.criticalIdentity)
        assertTrue(units.first { it.id == "partitions:system_a" }.logicalPartition)
    }

    @Test
    fun `source bytes are used when items carry no size`() {
        val raw = artifact("partitions:x", "partition", "x", listOf(item("partitions/x.img", null)), layout = "raw_stream", source = 999)
        assertEquals(999L, RestoreUnitGrouping.units(listOf(raw)).single().restoreBytes)
    }

    @Test
    fun `encrypted when the artifact or any item is encrypted`() {
        val plain = files("p", "Plain", 1)
        val enc = artifact("files:e", "files", "Enc", listOf(item("files/x", 1)), encrypted = true)
        val itemEnc = artifact("files:i", "files", "Item", listOf(item("files/y", 1, encrypted = true)))
        val units = RestoreUnitGrouping.units(listOf(plain, enc, itemEnc)).associateBy { it.id }
        assertFalse(units.getValue("files:p").encrypted)
        assertTrue(units.getValue("files:e").encrypted)
        assertTrue(units.getValue("files:i").encrypted)
    }

    @Test
    fun `groups are split by kind and sorted by display name`() {
        val units = RestoreUnitGrouping.units(listOf(app("org.zebra"), app("com.alpha"), partition("vendor_boot_a"), partition("boot_a")))
        val groups = RestoreUnitGrouping.group(units)
        assertEquals(listOf("com.alpha", "org.zebra"), groups.apps.map { it.displayName })
        assertEquals(listOf("boot_a", "vendor_boot_a"), groups.partitions.map { it.displayName })
        assertTrue(groups.files.isEmpty())
        assertFalse(groups.isEmpty)
        assertEquals(groups.partitions + groups.apps, groups.all)
    }

    @Test
    fun `restore bytes count only selected units and saturate`() {
        val units = listOf(
            RestoreTestData.unit("a", RestoreUnitKind.APP, 10),
            RestoreTestData.unit("b", RestoreUnitKind.APP, 20),
            RestoreTestData.unit("c", RestoreUnitKind.APP, Long.MAX_VALUE),
        )
        assertEquals(30L, RestoreUnitGrouping.restoreBytes(units, setOf("a", "b", "zzz")))
        assertEquals(Long.MAX_VALUE, RestoreUnitGrouping.restoreBytes(units, setOf("a", "c")))
    }

    @Test
    fun `package name ignores members that are not an app manifest`() {
        val odd = artifact("apps:x", "app", "x", listOf(item("apps/a/b/manifest.json"), item("files/apps/x/manifest.json")))
        assertNull(RestoreUnitGrouping.packageName(listOf(odd)))
    }
}
