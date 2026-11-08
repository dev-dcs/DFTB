package ca.devdcs.backup.core.discovery.apps

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.domain.model.AppComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppComponentAvailabilityTest {

    private fun env(
        tier: ElevationTier = ElevationTier.NONE,
        available: Boolean = tier != ElevationTier.NONE,
        uid: Int? = null,
        sdk: Int = 34,
        legacy: Boolean = false,
        read: Boolean = false,
        allFiles: Boolean = false,
        install: Boolean = false,
    ) = AccessEnvironment(tier, available, uid, sdk, legacy, read, allFiles, install)

    private fun reason(component: AppComponent, env: AccessEnvironment) =
        AppComponentAvailabilityResolver.resolve(component, env).reason

    @Test
    fun `apk is always available`() {
        ElevationTier.entries.forEach { tier ->
            assertTrue(AppComponentAvailabilityResolver.resolve(AppComponent.APK, env(tier, available = false)).available)
        }
    }

    @Test
    fun `standard mode on api 30+ cannot reach private or android-data`() {
        val e = env()
        assertEquals(ComponentAvailabilityReason.NEEDS_ROOT, reason(AppComponent.INTERNAL_DATA, e))
        assertEquals(ComponentAvailabilityReason.NEEDS_ROOT, reason(AppComponent.CACHE, e))
        assertEquals(ComponentAvailabilityReason.NEEDS_SHIZUKU_OR_ROOT, reason(AppComponent.EXTERNAL_DATA, e))
        assertEquals(ComponentAvailabilityReason.NEEDS_SHIZUKU_OR_ROOT, reason(AppComponent.OBB, e))
    }

    @Test
    fun `standard obb on api 30+ is unavailable even with install and all-files access`() {
        assertFalse(AppComponentAvailabilityResolver.resolve(AppComponent.OBB, env(allFiles = true, install = true)).available)
    }

    @Test
    fun `standard on api 29 with legacy storage reads android-data and obb`() {
        val e = env(sdk = 29, legacy = true, read = true)
        assertTrue(AppComponentAvailabilityResolver.resolve(AppComponent.EXTERNAL_DATA, e).available)
        assertTrue(AppComponentAvailabilityResolver.resolve(AppComponent.OBB, e).available)
        assertEquals(
            ComponentAvailabilityReason.NEEDS_LEGACY_STORAGE_ACCESS,
            reason(AppComponent.EXTERNAL_DATA, env(sdk = 29, legacy = true, read = false)),
        )
    }

    @Test
    fun `shizuku adb reaches shared dirs but not private data`() {
        val e = env(ElevationTier.SHIZUKU, uid = 2000)
        assertEquals(ComponentAvailabilityReason.SHIZUKU_ADB_CANNOT_READ_PRIVATE_DATA, reason(AppComponent.INTERNAL_DATA, e))
        assertEquals(ComponentAvailabilityReason.AVAILABLE, reason(AppComponent.EXTERNAL_DATA, e))
        assertEquals(ComponentAvailabilityReason.AVAILABLE, reason(AppComponent.OBB, e))
    }

    @Test
    fun `shizuku started by root reads everything`() {
        val all = AppComponentAvailabilityResolver.resolve(env(ElevationTier.SHIZUKU, uid = 0))
        assertTrue(all.values.all { it.available })
    }

    @Test
    fun `root reads everything`() {
        val all = AppComponentAvailabilityResolver.resolve(env(ElevationTier.ROOT, uid = 0))
        assertEquals(AppComponent.entries.toSet(), all.keys)
        assertTrue(all.values.all { it.available })
    }

    @Test
    fun `selected tier down reports elevation unavailable`() {
        val e = env(ElevationTier.ROOT, available = false)
        assertEquals(ComponentAvailabilityReason.ELEVATION_UNAVAILABLE, reason(AppComponent.INTERNAL_DATA, e))
        assertEquals(ComponentAvailabilityReason.ELEVATION_UNAVAILABLE, reason(AppComponent.EXTERNAL_DATA, e))
    }
}
