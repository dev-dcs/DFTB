package ca.devdcs.backup.core.discovery.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellScanTest {

    @Test
    fun `script quotes every package and path and uses user-specific dirs`() {
        val script = AppShellScanScript.build(listOf("com.example.app"), userId = 10, backingStore = false)
        assertTrue(script.contains("dftb_du 'com.example.app' ce '/data/user/10/com.example.app'"))
        assertTrue(script.contains("dftb_du 'com.example.app' de '/data/user_de/10/com.example.app'"))
        assertTrue(script.contains("dftb_du 'com.example.app' ext '/storage/emulated/10/Android/data/com.example.app'"))
        assertTrue(script.contains("dftb_du 'com.example.app' obb '/storage/emulated/10/Android/obb/com.example.app'"))
        assertTrue(script.contains("dftb_meta 'com.example.app' ce '/data/user/10/com.example.app'"))
        assertTrue(script.contains("dftb_meta 'com.example.app' de '/data/user_de/10/com.example.app'"))
        assertFalse(script.contains("dftb_meta 'com.example.app' ext"))
        assertTrue(script.startsWith("echo \"U|\$(id -u)\""))
    }

    @Test
    fun `user 0 with backing store uses data-data and data-media`() {
        val script = AppShellScanScript.build(listOf("a.b"), userId = 0, backingStore = true)
        assertTrue(script.contains("'/data/data/a.b'"))
        assertTrue(script.contains("'/data/data/a.b/cache'"))
        assertTrue(script.contains("'/data/media/0/Android/media/a.b'"))
    }

    @Test
    fun `invalid package names never reach the script`() {
        val script = AppShellScanScript.build(listOf("a.b'; rm -rf /; '", "../etc", "ok.pkg"), userId = 0, backingStore = false)
        assertFalse(script.contains("rm -rf"))
        assertFalse(script.contains("../etc"))
        assertTrue(script.contains("'ok.pkg'"))
    }

    @Test
    fun `chunks drop invalid and duplicate names`() {
        val chunks = AppShellScanScript.chunks(listOf("a.a", "b.b", "a.a", "bad name", "c.c"), chunkSize = 2)
        assertEquals(listOf(listOf("a.a", "b.b"), listOf("c.c")), chunks)
    }

    @Test
    fun `parser reads all record kinds`() {
        val out = AppShellScanParser.parse(
            listOf(
                "U|0",
                "S|com.x|ce|1024|0",
                "C|com.x|ce|10123|10123|u:object_r:app_data_file:s0:c123,c256,c512,c768",
                "S|com.x|de|8|0",
                "C|com.x|de|10123|10123|u:object_r:app_data_file:s0:c123,c256,c512,c768",
                "S|com.x|cache|200|0",
                "S|com.x|ext|50|1",
                "M|com.x|media",
                "D|com.x|obb",
                "garbage line",
                "",
            ),
        )
        assertEquals(0, out.shellUid)
        val m = out.measurements.getValue("com.x")
        assertEquals(LocationMeasurement.Measured(1024, true), m[AppDataLocation.CE_DATA])
        assertEquals(LocationMeasurement.Measured(50, false), m[AppDataLocation.EXTERNAL_DATA])
        assertEquals(LocationMeasurement.Missing, m[AppDataLocation.MEDIA])
        assertEquals(LocationMeasurement.Denied, m[AppDataLocation.OBB])
        val ce = out.metadata.getValue("com.x").getValue(AppDataLocation.CE_DATA)
        assertEquals(10123, ce.ownerUid)
        assertEquals(10123, ce.ownerGid)
        assertEquals("u:object_r:app_data_file:s0:c123,c256,c512,c768", ce.seLinuxContext)
        assertEquals(listOf("garbage line"), out.unparsedLines)
    }

    @Test
    fun `empty du output and unknown metadata parse to denied and nulls`() {
        val out = AppShellScanParser.parse(listOf("S|com.x|ce||1", "C|com.x|ce|||?", "S|com.x|bogus|1|0"))
        assertEquals(LocationMeasurement.Denied, out.measurements.getValue("com.x")[AppDataLocation.CE_DATA])
        val meta = out.metadata.getValue("com.x").getValue(AppDataLocation.CE_DATA)
        assertNull(meta.ownerUid)
        assertNull(meta.seLinuxContext)
        assertEquals(listOf("S|com.x|bogus|1|0"), out.unparsedLines)
    }

    @Test
    fun `size info maps missing to zero and unreadable to null`() {
        val info = AppShellScanParser.toSizeInfo(
            apkBytes = 100,
            splitBytes = 20,
            measurements = mapOf(
                AppDataLocation.CE_DATA to LocationMeasurement.Measured(4, false),
                AppDataLocation.DE_DATA to LocationMeasurement.Denied,
                AppDataLocation.CE_CACHE to LocationMeasurement.Denied,
                AppDataLocation.EXTERNAL_DATA to LocationMeasurement.Measured(10, true),
                AppDataLocation.MEDIA to LocationMeasurement.Missing,
                AppDataLocation.OBB to LocationMeasurement.Measured(1, true),
            ),
        )
        assertEquals(SizeSource.SHELL_DU, info.source)
        assertNull(info.internalDataBytes)
        assertNull(info.deviceProtectedBytes)
        assertEquals(10_240L, info.externalDataBytes)
        assertEquals(0L, info.mediaBytes)
        assertEquals(1_024L, info.obbBytes)
        assertEquals(setOf(AppDataLocation.CE_DATA, AppDataLocation.DE_DATA, AppDataLocation.CE_CACHE), info.unreadable)
        assertEquals(100L + 20L + 10_240L + 1_024L, info.totalKnownBytes)
    }

    @Test
    fun `non-root shell treats missing private dirs as unknown`() {
        val m = AppDataLocation.entries.associateWith { LocationMeasurement.Missing as LocationMeasurement }
        val info = AppShellScanParser.toSizeInfo(1, 0, m, privateDirsTrusted = false)
        assertNull(info.internalDataBytes)
        assertNull(info.deviceProtectedBytes)
        assertNull(info.cacheBytes)
        assertEquals(0L, info.externalDataBytes)
        assertEquals(0L, info.obbBytes)
        assertEquals(AppShellScanParser.PRIVATE_LOCATIONS, info.unreadable)
        assertEquals(0L, AppShellScanParser.toSizeInfo(1, 0, m).internalDataBytes)
    }

    @Test
    fun `obb presence is tri-state`() {
        assertEquals(true, AppShellScanParser.hasObb(mapOf(AppDataLocation.OBB to LocationMeasurement.Measured(0, true))))
        assertEquals(false, AppShellScanParser.hasObb(mapOf(AppDataLocation.OBB to LocationMeasurement.Missing)))
        assertNull(AppShellScanParser.hasObb(mapOf(AppDataLocation.OBB to LocationMeasurement.Denied)))
        assertNull(AppShellScanParser.hasObb(null))
    }

    @Test
    fun `merge keeps first uid and combines packages`() {
        val a = AppShellScanParser.parse(listOf("U|2000", "M|a.a|obb"))
        val b = AppShellScanParser.parse(listOf("U|2000", "M|b.b|obb"))
        val merged = AppShellScanParser.merge(listOf(a, b))
        assertEquals(2000, merged.shellUid)
        assertEquals(setOf("a.a", "b.b"), merged.measurements.keys)
    }

    @Test
    fun `package name validation`() {
        assertTrue(AppDataPaths.isValidPackageName("android"))
        assertTrue(AppDataPaths.isValidPackageName("com.google.android.gms"))
        assertTrue(AppDataPaths.isValidPackageName("ca.devdcs.backup.debug"))
        assertFalse(AppDataPaths.isValidPackageName(".."))
        assertFalse(AppDataPaths.isValidPackageName("com..x"))
        assertFalse(AppDataPaths.isValidPackageName("com.1x"))
        assertFalse(AppDataPaths.isValidPackageName("a/b"))
        assertFalse(AppDataPaths.isValidPackageName(""))
    }

    @Test
    fun `user id from uid`() {
        assertEquals(0, AppDataPaths.userIdOf(10_123))
        assertEquals(10, AppDataPaths.userIdOf(1_010_123))
    }
}
