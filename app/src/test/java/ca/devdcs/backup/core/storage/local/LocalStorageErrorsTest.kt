package ca.devdcs.backup.core.storage.local

import ca.devdcs.backup.core.storage.StorageException
import ca.devdcs.backup.core.storage.StorageException.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

class LocalStorageErrorsTest {

    @Test
    fun `errno text drives the reason`() {
        assertEquals(Reason.NO_SPACE, LocalStorageErrors.map("w", IOException("write failed: ENOSPC (No space left on device)")).reason)
        assertEquals(Reason.PERMISSION, LocalStorageErrors.map("w", IOException("open failed: EACCES (Permission denied)")).reason)
        assertEquals(Reason.PERMISSION, LocalStorageErrors.map("w", IOException("Read-only file system")).reason)
        assertEquals(Reason.NOT_FOUND, LocalStorageErrors.map("r", FileNotFoundException("/x: open failed: ENOENT (No such file or directory)")).reason)
    }

    @Test
    fun `cause chain is inspected`() {
        val wrapped = RuntimeException("outer", IOException("ENOSPC"))
        assertEquals(Reason.NO_SPACE, LocalStorageErrors.map("w", wrapped).reason)
    }

    @Test
    fun `exception types map when text is unhelpful`() {
        assertEquals(Reason.PERMISSION, LocalStorageErrors.map("x", SecurityException("nope")).reason)
        assertEquals(Reason.NOT_FOUND, LocalStorageErrors.map("x", FileNotFoundException("gone")).reason)
        assertEquals(Reason.UNSUPPORTED, LocalStorageErrors.map("x", UnsupportedOperationException()).reason)
        assertEquals(Reason.MISCONFIGURED, LocalStorageErrors.map("x", IllegalArgumentException("bad")).reason)
        assertEquals(Reason.PROTOCOL, LocalStorageErrors.map("x", IOException("weird")).reason)
    }

    @Test
    fun `storage exceptions pass through unchanged`() {
        val original = StorageException(Reason.AUTH, "a")
        assertSame(original, LocalStorageErrors.map("x", original))
    }

    @Test
    fun `guard rethrows cancellation and maps others`() {
        try {
            LocalStorageErrors.guard("op") { throw CancellationException("c") }
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals("c", e.message)
        }
        try {
            LocalStorageErrors.guard("op") { throw IOException("No space left on device") }
            fail("expected StorageException")
        } catch (e: StorageException) {
            assertEquals(Reason.NO_SPACE, e.reason)
            assertTrue(e.message!!.startsWith("Could not op"))
        }
    }

    @Test
    fun `wrapped streams surface StorageException`() {
        val failing = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("ENOSPC")
            }
        }
        try {
            LocalStorageErrors.mapErrors(failing, "write f").write(1)
            fail("expected StorageException")
        } catch (e: StorageException) {
            assertEquals(Reason.NO_SPACE, e.reason)
        }
        val input = LocalStorageErrors.mapErrors(ByteArrayInputStream(byteArrayOf(1, 2, 3)), "read f")
        assertEquals(1, input.read())
        assertEquals(2, input.read(ByteArray(4), 0, 4))
    }
}
