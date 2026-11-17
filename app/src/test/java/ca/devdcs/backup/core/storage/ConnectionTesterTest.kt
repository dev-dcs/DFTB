package ca.devdcs.backup.core.storage

import ca.devdcs.backup.domain.model.DestinationType
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTesterTest {

    private fun tester(backend: StorageBackend, repo: FakeDestinationRepository, timeout: Long = 20_000L): ConnectionTester {
        var nanos = 0L
        return ConnectionTester(DefaultStorageRegistry(setOf(backend)), repo, timeout, { 7L }) {
            nanos += 5_000_000L
            nanos
        }
    }

    @Test
    fun `success lists base reads space and records verification`() = runTest {
        val listed = mutableListOf<String>()
        val repo = FakeDestinationRepository()
        val backend = FakeBackend(setOf(DestinationType.LOCAL_INTERNAL)) {
            FakeSession(it, space = { SpaceInfo(10, 20) }, lister = { p -> listed += p; emptyList() })
        }
        val result = tester(backend, repo).test(testDestination())
        assertTrue(result.ok)
        assertNull(result.failure)
        assertEquals(SpaceInfo(10, 20), result.space)
        assertEquals(5L, result.latencyMillis)
        assertEquals(listOf(""), listed)
        assertEquals(1, repo.verifications.size)
    }

    @Test
    fun `storage exception is reported with its reason`() = runTest {
        val repo = FakeDestinationRepository()
        val backend = FakeBackend(setOf(DestinationType.SFTP)) {
            throw StorageException(StorageException.Reason.HOST_KEY, "fingerprint mismatch")
        }
        val result = tester(backend, repo).test(testDestination(DestinationType.SFTP))
        assertFalse(result.ok)
        assertEquals(StorageException.Reason.HOST_KEY, result.failure)
        assertEquals("fingerprint mismatch", result.detail)
        assertTrue(repo.verifications.isEmpty())
    }

    @Test
    fun `raw exception becomes PROTOCOL`() = runTest {
        val backend = FakeBackend(setOf(DestinationType.LOCAL_INTERNAL)) { error("boom") }
        val result = tester(backend, FakeDestinationRepository()).test(testDestination())
        assertEquals(StorageException.Reason.PROTOCOL, result.failure)
    }

    @Test
    fun `slow backend times out`() = runTest {
        val backend = FakeBackend(setOf(DestinationType.LOCAL_INTERNAL)) {
            delay(60_000L)
            FakeSession(it)
        }
        val result = tester(backend, FakeDestinationRepository()).test(testDestination())
        assertFalse(result.ok)
        assertEquals(StorageException.Reason.TIMEOUT, result.failure)
    }
}
