package ca.devdcs.backup.core.restore.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRestorePlanningTest {

    // ---- uid / gid / SELinux ---------------------------------------------------------------

    @Test
    fun `cache gid follows multiuser rules`() {
        assertEquals(20123, AppIds.cacheGid(10123))
        assertEquals(1_020_123, AppIds.cacheGid(1_010_123))
        assertNull(AppIds.cacheGid(1000))
        assertEquals(10, AppIds.userId(1_010_123))
        assertEquals(10123, AppIds.appId(1_010_123))
        assertEquals(1_010_123, AppIds.uid(10, 10123))
    }

    @Test
    fun `selinux categories match libselinux for app and user`() {
        assertEquals("c123,c256,c512,c768", AppIds.categories(10123, AppIds.LevelFrom.ALL))
        // appid 300 -> 300 & 0xff = 44, 256 + (300 >> 8) = 257; user 10 -> 522, 768.
        assertEquals("c44,c257,c522,c768", AppIds.categories(1_010_300, AppIds.LevelFrom.ALL))
        assertEquals("c522,c768", AppIds.categories(1_010_300, AppIds.LevelFrom.USER))
        assertEquals("c44,c257", AppIds.categories(1_010_300, AppIds.LevelFrom.APP))
    }

    @Test
    fun `relabel keeps type and shape but recomputes categories for the new uid`() {
        assertEquals(
            "u:object_r:app_data_file:s0:c200,c256,c512,c768",
            AppIds.relabel("u:object_r:app_data_file:s0:c123,c256,c512,c768", 10200),
        )
        assertEquals("u:object_r:app_data_file:s0:c522,c768", AppIds.relabel("u:object_r:app_data_file:s0:c512,c768", 1_010_200))
        assertEquals("u:object_r:privapp_data_file:s0", AppIds.relabel("u:object_r:privapp_data_file:s0", 10200))
        assertNull(AppIds.relabel("garbage", 10200))
        assertNull(AppIds.relabel("u:object_r:x:s0:c1,c2,c3", 10200))
    }

    // ---- signatures ----------------------------------------------------------------------

    @Test
    fun `signature comparison handles case, colons, rotation and multiple signers`() {
        assertEquals(SignatureMatch.SAME, SignatureCheck.compare(listOf("AB:CD"), emptyList(), listOf("abcd"), emptyList()))
        assertEquals(SignatureMatch.MISMATCH, SignatureCheck.compare(listOf("aa"), emptyList(), listOf("bb"), emptyList()))
        assertEquals(SignatureMatch.ROTATED, SignatureCheck.compare(listOf("new"), listOf("old", "new"), listOf("old"), emptyList()))
        assertEquals(SignatureMatch.ROTATED, SignatureCheck.compare(listOf("old"), emptyList(), listOf("new"), listOf("old")))
        assertEquals(SignatureMatch.MISMATCH, SignatureCheck.compare(listOf("a", "b"), emptyList(), listOf("a"), emptyList()))
        assertEquals(SignatureMatch.SAME, SignatureCheck.compare(listOf("a", "b"), emptyList(), listOf("b", "a"), emptyList()))
        assertEquals(SignatureMatch.UNKNOWN, SignatureCheck.compare(emptyList(), emptyList(), listOf("a"), emptyList()))
    }

    // ---- version policy ------------------------------------------------------------------

    private fun decide(
        backup: Long = 5,
        forUser: Long? = null,
        onDevice: Long? = forUser,
        policy: AppVersionPolicy = AppVersionPolicy.AUTO,
        downgrade: Boolean = false,
        privileged: Boolean = true,
        signature: SignatureMatch = SignatureMatch.SAME,
    ) = VersionDecision.decide(backup, forUser, onDevice, policy, restoreApks = true, apkInBackup = true, allowDowngrade = downgrade, signature = signature, privileged = privileged)

    @Test
    fun `version decision table`() {
        assertEquals(ApkDecision(ApkAction.INSTALL), decide(forUser = null))
        assertEquals(ApkDecision(ApkAction.INSTALL), decide(forUser = 4))
        assertEquals(ApkDecision(ApkAction.SKIP_SAME_VERSION), decide(forUser = 5))
        assertEquals(ApkDecision(ApkAction.INSTALL), decide(forUser = 5, policy = AppVersionPolicy.REINSTALL))
        assertEquals(ApkDecision(ApkAction.SKIP_INSTALLED_NEWER), decide(forUser = 6))
        assertEquals(ApkDecision(ApkAction.INSTALL, downgrade = true), decide(forUser = 6, downgrade = true))
        assertEquals(ApkDecision(ApkAction.SKIP_INSTALLED_NEWER), decide(forUser = 6, downgrade = true, privileged = false))
        assertEquals(ApkDecision(ApkAction.SKIP_POLICY), decide(policy = AppVersionPolicy.DATA_ONLY))
        assertEquals(ApkDecision(ApkAction.SKIP_SIGNATURE_MISMATCH), decide(forUser = 4, signature = SignatureMatch.MISMATCH))
        // Present for another user only.
        assertEquals(ApkDecision(ApkAction.INSTALL_EXISTING), decide(forUser = null, onDevice = 5))
        assertEquals(ApkDecision(ApkAction.INSTALL), decide(forUser = null, onDevice = 5, privileged = false))
        assertEquals(ApkDecision(ApkAction.SKIP_INSTALLED_NEWER), decide(forUser = null, onDevice = 7))
        assertEquals(
            ApkDecision(ApkAction.SKIP_NO_APK),
            VersionDecision.decide(5, null, null, AppVersionPolicy.AUTO, true, apkInBackup = false, allowDowngrade = false, signature = SignatureMatch.UNKNOWN, privileged = true),
        )
        assertEquals(
            ApkDecision(ApkAction.SKIP_POLICY),
            VersionDecision.decide(5, null, null, AppVersionPolicy.AUTO, restoreApks = false, apkInBackup = true, allowDowngrade = false, signature = SignatureMatch.UNKNOWN, privileged = true),
        )
    }

    // ---- split ordering --------------------------------------------------------------------

    @Test
    fun `apk plan puts base first, feature splits next, config splits last`() {
        val dir = "apps/com.example"
        val members = linkedMapOf<String, Long?>(
            "$dir/split_config.xxhdpi.apk" to 10L,
            "$dir/manifest.json" to 1L,
            "$dir/split_feature_b.apk" to 20L,
            "$dir/base.apk" to 100L,
            "$dir/split_config.arm64_v8a.apk" to 30L,
            "$dir/split_feature_a.apk" to 40L,
            "$dir/data.tar" to null,
            "$dir/external_data/split_x.apk" to 1L,
        )
        val plan = ApkSetPlanner.plan(dir, members, listOf("base.apk", "split_feature_b.apk", "split_feature_a.apk", "split_config.arm64_v8a.apk"))
        assertEquals(
            listOf("base.apk", "split_feature_b.apk", "split_feature_a.apk", "split_config.arm64_v8a.apk", "split_config.xxhdpi.apk"),
            plan.map { it.sessionName },
        )
        assertTrue(plan.first().isBase)
        assertEquals(200L, ApkSetPlanner.totalBytes(plan))
        assertEquals(listOf("split_gone.apk"), ApkSetPlanner.missing(plan, listOf("base.apk", "split_gone.apk")))
    }

    @Test
    fun `apk plan renames unsafe session names and leaves total unknown`() {
        val dir = "apps/p"
        val plan = ApkSetPlanner.plan(dir, mapOf("$dir/base.apk" to 1L, "$dir/split_we ird.apk" to null), emptyList())
        assertEquals("base.apk", plan[0].sessionName)
        assertEquals("split_1.apk", plan[1].sessionName)
        assertNull(ApkSetPlanner.totalBytes(plan))
        assertTrue(ApkSetPlanner.isApkMember(dir, "$dir/base.apk"))
        assertTrue(!ApkSetPlanner.isApkMember(dir, "$dir/obb/split_x.apk"))
    }

    // ---- permissions -----------------------------------------------------------------------

    @Test
    fun `permission plan keeps only requested grantable permissions, background last`() {
        val bg = "android.permission.ACCESS_BACKGROUND_LOCATION"
        val fine = "android.permission.ACCESS_FINE_LOCATION"
        val cam = "android.permission.CAMERA"
        val net = "android.permission.INTERNET"
        val dev = "android.permission.WRITE_SECURE_SETTINGS"
        val gone = "android.permission.READ_SMS"
        val undefined = "com.vendor.permission.X"
        val plan = PermissionPlanner.plan(
            recordedGranted = listOf(bg, fine, cam, net, dev, gone, undefined, fine),
            requestedByInstalled = setOf(bg, fine, cam, net, dev, undefined),
            currentlyGranted = setOf(cam),
            kinds = mapOf(
                bg to PermissionKind.RUNTIME, fine to PermissionKind.RUNTIME, cam to PermissionKind.RUNTIME,
                net to PermissionKind.SYSTEM_MANAGED, dev to PermissionKind.DEVELOPMENT, gone to PermissionKind.RUNTIME,
            ),
        )
        assertEquals(listOf(fine, dev, bg), plan.toGrant)
        assertEquals(listOf(cam), plan.alreadyGranted)
        assertEquals(listOf(gone), plan.notRequested)
        assertEquals(listOf(undefined), plan.undefined)
        assertEquals(listOf(net), plan.systemManaged)
    }

    // ---- protected packages / ownership --------------------------------------------------

    @Test
    fun `protected packages are classified`() {
        val self = "ca.devdcs.backup"
        assertEquals(ProtectedPackages.Verdict.REFUSED_SELF, ProtectedPackages.classify(self, self, false))
        assertEquals(ProtectedPackages.Verdict.REFUSED_PRIVILEGE_PROVIDER, ProtectedPackages.classify("com.topjohnwu.magisk", self, false))
        assertEquals(ProtectedPackages.Verdict.REFUSED_PRIVILEGE_PROVIDER, ProtectedPackages.classify("moe.shizuku.privileged.api", self, false))
        assertEquals(ProtectedPackages.Verdict.WARN_SYSTEM, ProtectedPackages.classify("com.android.providers.contacts", self, false))
        assertEquals(ProtectedPackages.Verdict.WARN_SYSTEM, ProtectedPackages.classify("com.oem.thing", self, true))
        assertEquals(ProtectedPackages.Verdict.ALLOWED, ProtectedPackages.classify("org.example.app", self, false))
    }

    @Test
    fun `external owner matches the existing dir, else parent group with app uid`() {
        val existing = Owner(10123, 1078)
        val parent = Owner(1023, 1078)
        assertEquals(existing, ExternalOwnership.ownerFor(ExternalPart.DATA, existing, parent, 10200))
        assertEquals(Owner(10200, 1078), ExternalOwnership.ownerFor(ExternalPart.DATA, null, parent, 10200))
        assertEquals(Owner(10200, 1079), ExternalOwnership.ownerFor(ExternalPart.OBB, null, Owner(1023, 1079), 10200))
        assertEquals(parent, ExternalOwnership.ownerFor(ExternalPart.MEDIA, null, parent, 10200))
        assertNull(ExternalOwnership.ownerFor(ExternalPart.DATA, null, null, 10200))
    }

    @Test
    fun `package is taken from the app manifest member only`() {
        assertEquals("com.example", DefaultAppRestorer.packageOf("apps/com.example/manifest.json"))
        assertNull(DefaultAppRestorer.packageOf("apps/com.example/base.apk"))
        assertNull(DefaultAppRestorer.packageOf("apps/a/b/manifest.json"))
        assertNull(DefaultAppRestorer.packageOf("apps/../manifest.json"))
        assertNull(DefaultAppRestorer.packageOf("files/x/manifest.json"))
    }
}
