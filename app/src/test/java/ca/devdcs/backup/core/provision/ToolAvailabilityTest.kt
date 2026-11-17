package ca.devdcs.backup.core.provision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ToolAvailabilityTest {

    @Test
    fun `absent is a not-found result for the given id`() {
        val absent = ToolAvailability.absent("rclone")
        assertEquals("rclone", absent.toolId)
        assertFalse(absent.available)
        assertNull(absent.path)
        assertEquals(ToolSource.ABSENT, absent.source)
        assertNull(absent.version)
    }

    @Test
    fun `copy attaches a version without disturbing the rest`() {
        val found = ToolAvailability("dftbtool", available = true, path = "/x/dftbtool", source = ToolSource.STAGED_TMP, version = null)
        val probed = found.copy(version = "dftbtool 1.0.0 (arm64-v8a)")
        assertEquals("dftbtool 1.0.0 (arm64-v8a)", probed.version)
        assertEquals(found.path, probed.path)
        assertEquals(found.source, probed.source)
        assertEquals(found.copy(version = probed.version), probed)
    }
}
