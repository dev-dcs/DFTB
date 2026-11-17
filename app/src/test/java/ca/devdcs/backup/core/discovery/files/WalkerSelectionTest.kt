package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.FileFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkerSelectionTest {

    private val pkg = "ca.devdcs.backup"

    private fun ctx(
        tier: ElevationTier = ElevationTier.NONE,
        privileged: Boolean = tier != ElevationTier.NONE,
        allFiles: Boolean = false,
        legacy: Boolean = false,
        sdk: Int = 34,
        media: Set<FileCategory> = emptySet(),
    ) = AccessContext(sdk, tier, privileged, allFiles, legacy, media)

    @Test
    fun `classifies locations`() {
        val c = LocationClassifier
        assertEquals(LocationClass.SAF, c.classify("content://com.android.externalstorage.documents/tree/primary%3ADCIM", pkg))
        assertEquals(LocationClass.SHARED_STORAGE, c.classify("/storage/emulated/0/DCIM/", pkg))
        assertEquals(LocationClass.SHARED_STORAGE, c.classify("/sdcard", pkg))
        assertEquals(LocationClass.SHARED_STORAGE, c.classify("/storage/1A2B-3C4D/Music", pkg))
        assertEquals(LocationClass.SHARED_APP_SANDBOX, c.classify("/storage/emulated/0/Android/data", pkg))
        assertEquals(LocationClass.SHARED_APP_SANDBOX, c.classify("/sdcard/Android/obb/com.game", pkg))
        assertEquals(LocationClass.APP_EXTERNAL_OWN, c.classify("/storage/emulated/0/Android/data/$pkg/files", pkg))
        assertEquals(LocationClass.SHARED_STORAGE, c.classify("/storage/emulated/0/Android/media", pkg))
        assertEquals(LocationClass.APP_PRIVATE, c.classify("/data/user/0/$pkg/files", pkg))
        assertEquals(LocationClass.APP_PRIVATE, c.classify("/data/data/$pkg", pkg))
        assertEquals(LocationClass.DATA_PARTITION, c.classify("/data/data/com.other", pkg))
        assertEquals(LocationClass.DATA_PARTITION, c.classify("/data/media/0", pkg))
        assertEquals(LocationClass.DATA_PARTITION, c.classify("/data", pkg))
        assertEquals(LocationClass.OTHER, c.classify("/system/etc", pkg))
        assertEquals(LocationClass.OTHER, c.classify("/database", pkg))
        assertEquals("/a/b", c.normalize("//a///b/"))
        assertEquals("/", c.normalize("/"))
    }

    @Test
    fun `shared storage prefers nio then find then reports requirements`() {
        assertEquals(WalkerDecision.Use(WalkerKind.NIO), WalkerSelector.select(LocationClass.SHARED_STORAGE, ctx(allFiles = true, tier = ElevationTier.ROOT)))
        assertEquals(WalkerDecision.Use(WalkerKind.NIO), WalkerSelector.select(LocationClass.SHARED_STORAGE, ctx(legacy = true, sdk = 29)))
        assertEquals(WalkerDecision.Use(WalkerKind.PRIVILEGED_FIND), WalkerSelector.select(LocationClass.SHARED_STORAGE, ctx(tier = ElevationTier.SHIZUKU)))
        val blocked = WalkerSelector.select(LocationClass.SHARED_STORAGE, ctx()) as WalkerDecision.Blocked
        assertEquals(BlockReason.NEEDS_ALL_FILES_ACCESS, blocked.reason)
        assertEquals(
            listOf(AccessRequirement.AllFilesAccess("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"), AccessRequirement.ElevatedTier(ElevationTier.SHIZUKU)),
            blocked.requirements,
        )
        val q = WalkerSelector.select(LocationClass.SHARED_STORAGE, ctx(sdk = 29)) as WalkerDecision.Blocked
        assertEquals(AccessRequirement.RuntimePermission(listOf(WalkerSelector.READ_EXTERNAL_STORAGE)), q.requirements.first())
    }

    @Test
    fun `app sandbox subtrees prefer find on API 30 plus`() {
        assertEquals(listOf("Android/data", "Android/obb"), LocationClassifier.appSandboxDirsBelow("/sdcard/"))
        assertEquals(listOf("data", "obb"), LocationClassifier.appSandboxDirsBelow("/storage/emulated/0/Android"))
        assertTrue(LocationClassifier.appSandboxDirsBelow("/sdcard/DCIM").isEmpty())
        assertTrue(LocationClassifier.appSandboxDirsBelow("content://x/tree/y").isEmpty())
        val rootAll = ctx(tier = ElevationTier.ROOT, allFiles = true)
        assertEquals(WalkerDecision.Use(WalkerKind.PRIVILEGED_FIND), WalkerSelector.select(LocationClass.SHARED_STORAGE, rootAll, containsAppSandbox = true))
        assertEquals(WalkerDecision.Use(WalkerKind.NIO), WalkerSelector.select(LocationClass.SHARED_STORAGE, rootAll, containsAppSandbox = false))
        assertEquals(WalkerDecision.Use(WalkerKind.NIO), WalkerSelector.select(LocationClass.SHARED_STORAGE, ctx(allFiles = true), containsAppSandbox = true))
        assertEquals(WalkerDecision.Use(WalkerKind.NIO), WalkerSelector.select(LocationClass.SHARED_STORAGE, ctx(tier = ElevationTier.ROOT, legacy = true, sdk = 29), containsAppSandbox = true))
    }

    @Test
    fun `canonicalizes primary storage aliases`() {
        val p = "/storage/emulated/0"
        assertEquals("/storage/emulated/0/DCIM", LocationClassifier.canonicalize("/sdcard/DCIM/", p))
        assertEquals("/storage/emulated/0", LocationClassifier.canonicalize("/storage/self/primary", p))
        assertEquals("/storage/emulated/0/x", LocationClassifier.canonicalize("/mnt/sdcard//x", p))
        assertEquals("/sdcardX", LocationClassifier.canonicalize("/sdcardX", p))
        assertEquals("content://a/tree/b", LocationClassifier.canonicalize("content://a/tree/b", p))
    }

    @Test
    fun `unavailable privileged shell does not count`() {
        val decision = WalkerSelector.select(LocationClass.SHARED_STORAGE, ctx(tier = ElevationTier.SHIZUKU, privileged = false))
        assertTrue(decision is WalkerDecision.Blocked)
    }

    @Test
    fun `app sandbox data partition and other`() {
        assertEquals(WalkerDecision.Use(WalkerKind.NIO), WalkerSelector.select(LocationClass.APP_PRIVATE, ctx()))
        assertEquals(WalkerDecision.Use(WalkerKind.NIO), WalkerSelector.select(LocationClass.APP_EXTERNAL_OWN, ctx()))
        assertEquals(WalkerDecision.Use(WalkerKind.SAF), WalkerSelector.select(LocationClass.SAF, ctx()))
        val sandbox = WalkerSelector.select(LocationClass.SHARED_APP_SANDBOX, ctx(allFiles = true)) as WalkerDecision.Blocked
        assertEquals(BlockReason.NEEDS_ELEVATION_FOR_APP_SANDBOX, sandbox.reason)
        assertEquals(WalkerDecision.Use(WalkerKind.NIO), WalkerSelector.select(LocationClass.SHARED_APP_SANDBOX, ctx(legacy = true, sdk = 29)))
        assertEquals(WalkerDecision.Use(WalkerKind.PRIVILEGED_FIND), WalkerSelector.select(LocationClass.SHARED_APP_SANDBOX, ctx(tier = ElevationTier.SHIZUKU)))
        val data = WalkerSelector.select(LocationClass.DATA_PARTITION, ctx(tier = ElevationTier.SHIZUKU)) as WalkerDecision.Blocked
        assertEquals(BlockReason.NEEDS_ROOT, data.reason)
        assertEquals(listOf(AccessRequirement.ElevatedTier(ElevationTier.ROOT)), data.requirements)
        assertEquals(WalkerDecision.Use(WalkerKind.PRIVILEGED_FIND), WalkerSelector.select(LocationClass.DATA_PARTITION, ctx(tier = ElevationTier.ROOT)))
        assertEquals(WalkerDecision.Use(WalkerKind.NIO), WalkerSelector.select(LocationClass.OTHER, ctx(), rootReadableInProcess = true))
        val other = WalkerSelector.select(LocationClass.OTHER, ctx(), rootReadableInProcess = false) as WalkerDecision.Blocked
        assertEquals(BlockReason.NOT_READABLE, other.reason)
    }

    @Test
    fun `category roots fall back to MediaStore for granted media only`() {
        val cats = setOf(FileCategory.IMAGES, FileCategory.VIDEOS, FileCategory.DOCUMENTS)
        val d = WalkerSelector.selectForCategoryRoot(cats, ctx(media = setOf(FileCategory.IMAGES)))
        assertEquals(WalkerKind.MEDIA_STORE, d.walker)
        assertEquals(setOf(FileCategory.IMAGES), d.covered)
        assertEquals(setOf(FileCategory.VIDEOS, FileCategory.DOCUMENTS), d.uncovered)
        assertEquals(BlockReason.NEEDS_MEDIA_PERMISSION, d.reason)
        assertEquals(AccessRequirement.RuntimePermission(listOf(WalkerSelector.READ_MEDIA_VIDEO)), d.requirements[0])
        assertTrue(d.requirements.contains(AccessRequirement.AllFilesAccess()))

        val docsOnly = WalkerSelector.selectForCategoryRoot(setOf(FileCategory.DOCUMENTS), ctx(media = WalkerSelector.MEDIA_CATEGORIES))
        assertNull(docsOnly.walker)
        assertEquals(BlockReason.NEEDS_ALL_FILES_ACCESS_FOR_NON_MEDIA, docsOnly.reason)

        val full = WalkerSelector.selectForCategoryRoot(cats, ctx(allFiles = true))
        assertEquals(WalkerKind.NIO, full.walker)
        assertTrue(full.uncovered.isEmpty())
        assertNull(full.reason)

        val allMedia = WalkerSelector.selectForCategoryRoot(setOf(FileCategory.AUDIO), ctx(media = setOf(FileCategory.AUDIO)))
        assertNull(allMedia.reason)
        assertTrue(allMedia.requirements.isEmpty())
    }

    @Test
    fun `media permissions per sdk`() {
        assertEquals(listOf(WalkerSelector.READ_EXTERNAL_STORAGE), WalkerSelector.mediaPermissions(setOf(FileCategory.AUDIO), 32))
        assertEquals(
            listOf(WalkerSelector.READ_MEDIA_IMAGES, WalkerSelector.READ_MEDIA_AUDIO),
            WalkerSelector.mediaPermissions(setOf(FileCategory.AUDIO, FileCategory.IMAGES, FileCategory.DOCUMENTS), 33),
        )
    }

    @Test
    fun `category plan merges roots and filters`() {
        val roots = CategoryRoots.plan(setOf(FileCategory.VIDEOS, FileCategory.IMAGES, FileCategory.AUDIO), "/storage/emulated/0/", null)
        assertEquals(
            listOf("/storage/emulated/0/DCIM", "/storage/emulated/0/Pictures", "/storage/emulated/0/Movies", "/storage/emulated/0/Music"),
            roots.map { it.location },
        )
        val dcim = roots.first()
        assertEquals(setOf(FileCategory.IMAGES, FileCategory.VIDEOS), dcim.categories)
        assertTrue("jpg" in dcim.filter.includeExtensions && "mp4" in dcim.filter.includeExtensions)
        assertTrue("mp3" !in dcim.filter.includeExtensions)
    }

    @Test
    fun `effective filter intersects user includes and keeps other criteria`() {
        val user = FileFilter(includeExtensions = setOf(".JPG", "pdf"), minSizeBytes = 10)
        val f = CategoryRoots.effectiveFilter(setOf(FileCategory.IMAGES), user)
        assertEquals(setOf("jpg"), f.includeExtensions)
        assertEquals(10L, f.minSizeBytes)
        val none = CategoryRoots.effectiveFilter(setOf(FileCategory.AUDIO), user)
        val compiled = none.compile()
        assertTrue(!compiled.matches("a.mp3", 100, 1) && !compiled.matches("a.pdf", 100, 1) && !compiled.matches("noext", 100, 1))
    }

    @Test
    fun `media store helpers`() {
        assertEquals("DCIM/", MediaStoreFileWalker.mediaRelativeRoot("/storage/emulated/0/DCIM", "/storage/emulated/0"))
        assertEquals("DCIM/Camera/", MediaStoreFileWalker.mediaRelativeRoot("/sdcard/DCIM/Camera", "/storage/emulated/0"))
        assertEquals("", MediaStoreFileWalker.mediaRelativeRoot("/storage/emulated/0", "/storage/emulated/0"))
        assertNull(MediaStoreFileWalker.mediaRelativeRoot("/storage/1A2B-3C4D/DCIM", "/storage/emulated/0"))
        assertEquals("media_type IN (?,?) AND relative_path LIKE ? ESCAPE '\\'", MediaStoreFileWalker.buildSelection(2, recursive = true, volumeRoot = false))
        assertEquals("media_type IN (?) AND relative_path = ?", MediaStoreFileWalker.buildSelection(1, recursive = false, volumeRoot = false))
        assertEquals(listOf("1", "3", "My\\_Dir\\%/%"), MediaStoreFileWalker.buildArgs(listOf(1, 3), "My_Dir%/", recursive = true).toList())
        assertEquals(1, MediaStoreFileWalker.mediaTypeOf(FileCategory.IMAGES))
        assertNull(MediaStoreFileWalker.mediaTypeOf(FileCategory.DOCUMENTS))
    }
}
