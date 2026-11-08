package ca.devdcs.backup.core.storage.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafVolumesTest {

    private val ext = SafVolumes.EXTERNAL_STORAGE_AUTHORITY

    @Test
    fun `volume id is parsed from external storage document ids`() {
        assertEquals("primary", SafVolumes.volumeIdFromDocumentId(ext, "primary:"))
        assertEquals("primary", SafVolumes.volumeIdFromDocumentId(ext, "primary:Backups/DFTB"))
        assertEquals("1A2B-3C4D", SafVolumes.volumeIdFromDocumentId(ext, "1A2B-3C4D:"))
    }

    @Test
    fun `other providers and malformed ids yield null`() {
        assertNull(SafVolumes.volumeIdFromDocumentId("com.google.android.apps.docs.storage", "acc=1;doc=abc:def"))
        assertNull(SafVolumes.volumeIdFromDocumentId(ext, ":nothing"))
        assertNull(SafVolumes.volumeIdFromDocumentId(ext, "noColon"))
        assertNull(SafVolumes.volumeIdFromDocumentId(ext, null))
        assertNull(SafVolumes.volumeIdFromDocumentId(null, "primary:"))
    }

    @Test
    fun `volume matching`() {
        assertTrue(SafVolumes.matches("primary", null, isPrimary = true))
        assertFalse(SafVolumes.matches("primary", "1A2B-3C4D", isPrimary = false))
        assertTrue(SafVolumes.matches("1a2b-3c4d", "1A2B-3C4D", isPrimary = false))
        assertFalse(SafVolumes.matches("1A2B-3C4D", null, isPrimary = true))
    }
}
