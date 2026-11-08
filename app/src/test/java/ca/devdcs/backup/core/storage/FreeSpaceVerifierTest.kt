package ca.devdcs.backup.core.storage

import ca.devdcs.backup.domain.model.DestinationType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeSpaceVerifierTest {

    private val mib = 1024L * 1024

    @Test
    fun `margin uses the minimum for small payloads`() {
        assertEquals(10 * mib + 64 * mib, FreeSpaceVerifier.requiredWithMargin(10 * mib, 0.05, 64 * mib))
        assertEquals(64 * mib, FreeSpaceVerifier.requiredWithMargin(0, 0.05, 64 * mib))
    }

    @Test
    fun `margin uses the fraction for large payloads and rounds up`() {
        val tenGib = 10L * 1024 * mib
        // 0.25 is exact in binary floating point, so the expected value is exact too.
        assertEquals(tenGib + 2560 * mib, FreeSpaceVerifier.requiredWithMargin(tenGib, 0.25, 64 * mib))
        assertEquals(101L + 1L, FreeSpaceVerifier.requiredWithMargin(101, 0.001, 0))
    }

    @Test
    fun `margin saturates instead of overflowing`() {
        assertEquals(Long.MAX_VALUE, FreeSpaceVerifier.requiredWithMargin(Long.MAX_VALUE - 1, 0.05, 64 * mib))
        assertEquals(Long.MAX_VALUE, FreeSpaceVerifier.requiredWithMargin(Long.MAX_VALUE, 10.0, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative required is rejected`() {
        FreeSpaceVerifier.requiredWithMargin(-1, 0.05, 0)
    }

    @Test
    fun `evaluate classifies sufficient insufficient and unknown`() {
        val space = SpaceInfo(freeBytes = 200 * mib, totalBytes = 1000 * mib)
        assertTrue(FreeSpaceVerifier.evaluate(space, 100 * mib) is PreflightResult.Sufficient)

        val short = FreeSpaceVerifier.evaluate(space, 150 * mib)
        assertTrue(short is PreflightResult.Insufficient)
        assertEquals(150 * mib + 64 * mib - 200 * mib, (short as PreflightResult.Insufficient).shortfallBytes)

        assertTrue(FreeSpaceVerifier.evaluate(null, 1) is PreflightResult.Unknown)
        assertTrue(FreeSpaceVerifier.evaluate(SpaceInfo(null, 10), 1) is PreflightResult.Unknown)
    }

    @Test
    fun `verify records observed capacity`() = runTest {
        val repo = FakeDestinationRepository()
        val backend = FakeBackend(setOf(DestinationType.LOCAL_INTERNAL)) {
            FakeSession(it, space = { SpaceInfo(500 * mib, 1000 * mib) })
        }
        val verifier = FreeSpaceVerifier(DefaultStorageRegistry(setOf(backend)), repo) { 99L }
        val result = verifier.verify(testDestination(), 100 * mib)
        assertTrue(result is PreflightResult.Sufficient)
        assertEquals(listOf(FakeDestinationRepository.Verification("d1", 99L, 500 * mib, 1000 * mib)), repo.verifications)
    }

    @Test
    fun `verify maps connection failure to Unknown without recording`() = runTest {
        val repo = FakeDestinationRepository()
        val backend = FakeBackend(setOf(DestinationType.LOCAL_INTERNAL)) {
            throw StorageException(StorageException.Reason.AUTH, "bad password")
        }
        val verifier = FreeSpaceVerifier(DefaultStorageRegistry(setOf(backend)), repo) { 0L }
        val result = verifier.verify(testDestination(), 1)
        assertEquals(PreflightResult.Unknown("bad password", StorageException.Reason.AUTH), result)
        assertTrue(repo.verifications.isEmpty())
    }
}
