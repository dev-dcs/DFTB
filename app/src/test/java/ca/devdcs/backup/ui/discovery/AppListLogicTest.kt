package ca.devdcs.backup.ui.discovery

import ca.devdcs.backup.core.discovery.apps.AppKind
import ca.devdcs.backup.core.discovery.apps.AppSizeInfo
import ca.devdcs.backup.core.discovery.apps.AppSpecialFlags
import ca.devdcs.backup.core.discovery.apps.InstallSource
import ca.devdcs.backup.core.discovery.apps.InstalledApp
import ca.devdcs.backup.core.discovery.apps.SizeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListLogicTest {

    private fun app(
        pkg: String,
        label: String,
        kind: AppKind = AppKind.USER,
        split: Boolean = false,
        obb: Boolean? = false,
        updated: Long = 0L,
    ) = InstalledApp(
        packageName = pkg,
        label = label,
        kind = kind,
        enabled = true,
        versionName = "1.0",
        versionCode = 1,
        targetSdk = 35,
        minSdk = 29,
        uid = 10_100,
        userId = 0,
        installSource = InstallSource(null, null, null, null),
        firstInstallTimeMillis = 0L,
        lastUpdateTimeMillis = updated,
        baseApkPath = "/data/app/$pkg/base.apk",
        splitApkPaths = if (split) listOf("/data/app/$pkg/split_config.arm64_v8a.apk") else emptyList(),
        splitNames = if (split) listOf("config.arm64_v8a") else emptyList(),
        obbDirPath = "/storage/emulated/0/Android/obb/$pkg",
        nativeLibraryDir = null,
        flags = AppSpecialFlags(
            isSplitBundle = split,
            hasObb = obb,
            isSelf = false,
            isRootManager = false,
            isCriticalSystem = false,
            allowBackup = true,
            isDebuggable = false,
            isApex = false,
        ),
    )

    private fun size(bytes: Long) = AppSizeInfo(apkBytes = bytes, splitBytes = 0, source = SizeSource.APK_ONLY)

    private val camera = app("com.example.camera", "Camera", AppKind.SYSTEM, updated = 300)
    private val zebra = app("org.zebra", "zebra", AppKind.USER, split = true, updated = 100)
    private val game = app("com.game.big", "Big Game", AppKind.USER, split = true, obb = true, updated = 200)
    private val chrome = app("com.android.chrome", "Chrome", AppKind.UPDATED_SYSTEM, obb = null, updated = 400)
    private val all = listOf(camera, zebra, game, chrome)

    private fun names(list: List<InstalledApp>) = list.map { it.label }

    @Test
    fun nameSortIsCaseInsensitive() {
        val result = AppListLogic.apply(all, emptyMap(), AppListQuery())
        assertEquals(listOf("Big Game", "Camera", "Chrome", "zebra"), names(result))
    }

    @Test
    fun searchMatchesLabelOrPackage() {
        assertEquals(listOf("Big Game"), names(AppListLogic.apply(all, emptyMap(), AppListQuery(search = " game "))))
        assertEquals(listOf("Chrome"), names(AppListLogic.apply(all, emptyMap(), AppListQuery(search = "ANDROID.chr"))))
        assertTrue(AppListLogic.apply(all, emptyMap(), AppListQuery(search = "nothing")).isEmpty())
    }

    @Test
    fun kindFiltersAreOredTogether() {
        val q = AppListQuery(filters = setOf(AppListFilter.SYSTEM, AppListFilter.UPDATED_SYSTEM))
        assertEquals(listOf("Camera", "Chrome"), names(AppListLogic.apply(all, emptyMap(), q)))
    }

    @Test
    fun splitAndObbNarrowTheKindSelection() {
        val q = AppListQuery(filters = setOf(AppListFilter.USER, AppListFilter.SPLIT))
        assertEquals(listOf("Big Game", "zebra"), names(AppListLogic.apply(all, emptyMap(), q)))
        val obb = AppListQuery(filters = setOf(AppListFilter.HAS_OBB))
        // Unknown OBB presence (null) never counts as "has OBB".
        assertEquals(listOf("Big Game"), names(AppListLogic.apply(all, emptyMap(), obb)))
    }

    @Test
    fun sizeSortPutsLargestFirstAndUnmeasuredLast() {
        val sizes = mapOf(zebra.packageName to size(10), game.packageName to size(5_000), camera.packageName to size(10))
        val result = AppListLogic.apply(all, sizes, AppListQuery(sort = AppSort.SIZE))
        // Camera and zebra tie on size and fall back to name order; Chrome has no size.
        assertEquals(listOf("Big Game", "Camera", "zebra", "Chrome"), names(result))
    }

    @Test
    fun updatedSortIsNewestFirst() {
        val result = AppListLogic.apply(all, emptyMap(), AppListQuery(sort = AppSort.UPDATED))
        assertEquals(listOf("Chrome", "Camera", "Big Game", "zebra"), names(result))
    }

    @Test
    fun toggleAddsAndRemoves() {
        val on = AppListLogic.toggle(emptySet(), AppListFilter.SPLIT)
        assertTrue(AppListFilter.SPLIT in on)
        assertFalse(AppListFilter.SPLIT in AppListLogic.toggle(on, AppListFilter.SPLIT))
    }
}
