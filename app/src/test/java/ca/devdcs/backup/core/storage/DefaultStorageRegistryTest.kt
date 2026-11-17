package ca.devdcs.backup.core.storage

import ca.devdcs.backup.domain.model.DestinationType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DefaultStorageRegistryTest {

    @Test
    fun `routes each type to its backend`() {
        val local = FakeBackend(setOf(DestinationType.LOCAL_INTERNAL)) { FakeSession(it) }
        val saf = FakeBackend(setOf(DestinationType.LOCAL_SAF, DestinationType.USB_OTG)) { FakeSession(it) }
        val registry = DefaultStorageRegistry(setOf(local, saf))
        assertSame(local, registry.backendFor(DestinationType.LOCAL_INTERNAL))
        assertSame(saf, registry.backendFor(DestinationType.USB_OTG))
        assertEquals(3, registry.supportedTypes.size)
    }

    @Test
    fun `duplicate claims fail fast`() {
        val a = FakeBackend(setOf(DestinationType.SFTP)) { FakeSession(it) }
        val b = FakeBackend(setOf(DestinationType.SFTP, DestinationType.SSH)) { FakeSession(it) }
        try {
            DefaultStorageRegistry(setOf(a, b))
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("SFTP"))
        }
    }

    @Test
    fun `unserved type is UNSUPPORTED`() {
        val registry = DefaultStorageRegistry(emptySet())
        try {
            registry.backendFor(DestinationType.NFS)
            fail("expected StorageException")
        } catch (e: StorageException) {
            assertEquals(StorageException.Reason.UNSUPPORTED, e.reason)
        }
    }

    @Test
    fun `withSession closes on success and failure`() = runTest {
        val sessions = mutableListOf<FakeSession>()
        val backend = FakeBackend(setOf(DestinationType.LOCAL_INTERNAL)) { FakeSession(it).also(sessions::add) }
        val registry = DefaultStorageRegistry(setOf(backend))

        assertEquals(42, registry.withSession(testDestination()) { 42 })
        assertTrue(sessions[0].closed)

        try {
            registry.withSession(testDestination()) { throw StorageException(StorageException.Reason.NETWORK, "x") }
            fail("expected StorageException")
        } catch (e: StorageException) {
            assertEquals(StorageException.Reason.NETWORK, e.reason)
        }
        assertTrue(sessions[1].closed)
    }
}
