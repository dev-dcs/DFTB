package ca.devdcs.backup.core.pipeline.layout

import ca.devdcs.backup.core.pipeline.layout.ArchiveLayout.AppArchivePart
import ca.devdcs.backup.domain.model.FileCategory
import ca.devdcs.backup.domain.model.StructureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveLayoutTest {

    private val preserve = ArchiveLayout(StructureMode.PRESERVE_HIERARCHY)
    private val categorized = ArchiveLayout(StructureMode.CATEGORIZED)

    @Test
    fun `partition and app paths`() {
        assertEquals("partitions/boot_a.img", preserve.partitionImage("boot_a", logical = false))
        assertEquals("partitions/mapper/userdata.img", preserve.partitionImage("userdata", logical = true))
        assertEquals("apps/com.example/base.apk", preserve.appBaseApk("com.example"))
        assertEquals("apps/com.example/split_config.arm64_v8a.apk", preserve.appSplitApk("com.example", "config.arm64_v8a"))
        assertEquals("apps/com.example/data.tar", preserve.appArchive("com.example", AppArchivePart.CE_DATA))
        assertEquals("apps/com.example/data_de.tar", preserve.appArchive("com.example", AppArchivePart.DE_DATA))
        assertEquals("apps/com.example/manifest.json", preserve.appManifest("com.example"))
        assertEquals("apps/com.example/obb/main.1.obb", preserve.appTreeEntry("com.example", AppArchivePart.OBB, "main.1.obb"))
        // Layout of partitions/apps is independent of the file structure mode.
        assertEquals(preserve.appBaseApk("a.b"), categorized.appBaseApk("a.b"))
    }

    @Test
    fun `segments never escape the archive root`() {
        assertEquals("_", ArchiveLayout.segment(""))
        assertEquals("_.", ArchiveLayout.segment("."))
        assertEquals("_..", ArchiveLayout.segment(".."))
        assertEquals("a_b_c", ArchiveLayout.segment("a/b\\c"))
        assertEquals("x_y", ArchiveLayout.segment("x\u0000y"))
        assertEquals("a/_../b", ArchiveLayout.relative("/a/../b/"))
        assertEquals("_", ArchiveLayout.relative("///"))
    }

    @Test
    fun `root labels from paths and saf uris`() {
        assertEquals("DCIM", ArchiveLayout.rootLabel("/storage/emulated/0/DCIM/"))
        assertEquals("Camera", ArchiveLayout.rootLabel("content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCamera"))
        assertEquals("primary", ArchiveLayout.rootLabel("content://com.android.externalstorage.documents/tree/primary%3A"))
        assertEquals("root", ArchiveLayout.rootLabel("/"))
    }

    @Test
    fun `preserve hierarchy mirrors the source and separates same-named roots`() {
        val a = preserve.newFileAllocator()
        assertEquals("files/DCIM/Camera/a.jpg", a.allocate("/sdcard/DCIM", "Camera/a.jpg", FileCategory.IMAGES))
        assertEquals("files/DCIM-2/b.jpg", a.allocate("/storage/1234-ABCD/DCIM", "b.jpg", FileCategory.IMAGES))
        assertEquals("files/DCIM/Camera/c.jpg", a.allocate("/sdcard/DCIM", "Camera/c.jpg", FileCategory.IMAGES))
    }

    @Test
    fun `case-insensitive collisions are renamed`() {
        val a = preserve.newFileAllocator()
        assertEquals("files/X/Readme.txt", a.allocate("/r/X", "Readme.txt", null))
        assertEquals("files/X/readme (2).txt", a.allocate("/r/X", "readme.txt", null))
    }

    @Test
    fun `directory colliding with an earlier file is renamed consistently`() {
        val a = preserve.newFileAllocator()
        assertEquals("files/X/A", a.allocate("/r/X", "A", null))
        assertEquals("files/X/a (2)/b", a.allocate("/r/X", "a/b", null))
        assertEquals("files/X/a (2)/c", a.allocate("/r/X", "a/c", null))
    }

    @Test
    fun `categorized groups by category with collision-safe names`() {
        val a = categorized.newFileAllocator()
        assertEquals("files/Images/IMG_1.jpg", a.allocate("/sdcard/DCIM", "Camera/IMG_1.jpg", FileCategory.IMAGES))
        assertEquals("files/Images/IMG_1 (2).jpg", a.allocate("/sdcard/Pictures", "IMG_1.jpg", FileCategory.IMAGES))
        assertEquals("files/Images/img_1 (3).jpg", a.allocate("/sdcard/Download", "img_1.jpg", FileCategory.IMAGES))
        assertEquals("files/Documents/cv.pdf", a.allocate("/sdcard/Download", "cv.pdf", FileCategory.DOCUMENTS))
        assertEquals("files/Other/notes", a.allocate("/sdcard/Download", "notes", null))
        assertEquals("files/Other/notes (2)", a.allocate("/sdcard/Documents", "notes", null))
    }

    @Test
    fun `numbered keeps the extension and handles dot files`() {
        assertEquals("a.tar (2).gz", FilePathAllocator.numbered("a.tar.gz", 2))
        assertEquals(".nomedia (2)", FilePathAllocator.numbered(".nomedia", 2))
        assertEquals("Makefile (3)", FilePathAllocator.numbered("Makefile", 3))
    }

    @Test
    fun `parents exclude the leaf`() {
        val a = preserve.newFileAllocator()
        assertEquals(listOf("files", "files/X", "files/X/y"), a.parentsOf("files/X/y/z.txt"))
        assertTrue(a.parentsOf("single").isEmpty())
        assertFalse(a.parentsOf("files/X/y/z.txt").contains("files/X/y/z.txt"))
    }

    @Test
    fun `every category has a distinct folder`() {
        val folders = FileCategory.entries.map { ArchiveLayout.categoryFolder(it) } + ArchiveLayout.categoryFolder(null)
        assertEquals(folders.size, folders.toSet().size)
        assertEquals("Images", ArchiveLayout.categoryFolder(FileCategory.IMAGES))
        assertEquals("Other", ArchiveLayout.categoryFolder(null))
    }
}
