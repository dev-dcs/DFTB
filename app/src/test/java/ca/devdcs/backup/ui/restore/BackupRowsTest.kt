package ca.devdcs.backup.ui.restore

import ca.devdcs.backup.core.restore.BackupSummary
import ca.devdcs.backup.core.restore.catalog.DefaultBackupCatalog
import ca.devdcs.backup.domain.model.Destination
import ca.devdcs.backup.domain.model.DestinationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupRowsTest {

    private fun listed(dir: String, created: Long?, partial: Boolean? = false, readable: Boolean = true) = DefaultBackupCatalog.ListedBackup(
        BackupSummary(
            destinationId = "d",
            jobDir = dir,
            manifestPath = "$dir/dftb-manifest.json",
            manifestReadable = readable,
            createdAtMillis = created,
            profileName = if (readable) "Profile" else null,
            totalBytes = if (readable) 10L else null,
            encrypted = !readable,
        ),
        partial,
    )

    @Test
    fun `rows are newest first with undated backups last`() {
        val rows = BackupRows.of(
            listOf(
                listed("Profile_2026-01-01", 1_000L),
                listed("Locked_x", null, partial = null, readable = false),
                listed("Profile_2026-03-01", 3_000L, partial = true),
                listed("Profile_2026-02-01", 2_000L),
            ),
        )
        assertEquals(listOf("Profile_2026-03-01", "Profile_2026-02-01", "Profile_2026-01-01", "Locked_x"), rows.map { it.jobDir })
        assertEquals(true, rows.first().partial)
        assertNull(rows.last().partial)
        assertEquals(false, rows.last().manifestReadable)
    }

    @Test
    fun `equal dates fall back to the folder name, newest stamp first`() {
        val rows = BackupRows.of(listOf(listed("a/P_1", 5L), listed("a/P_2", 5L)))
        assertEquals(listOf("P_2", "P_1"), rows.map { it.folderName })
    }

    private fun destination(id: String) = Destination(id = id, name = id, type = DestinationType.LOCAL_INTERNAL, createdAt = 0L, updatedAt = 0L)

    @Test
    fun `saved destination wins while it exists, else the first`() {
        val list = listOf(destination("a"), destination("b"))
        assertEquals("b", BackupRows.pickDestination(list, "b"))
        assertEquals("a", BackupRows.pickDestination(list, "gone"))
        assertEquals("a", BackupRows.pickDestination(list, null))
        assertNull(BackupRows.pickDestination(emptyList(), "b"))
    }
}
