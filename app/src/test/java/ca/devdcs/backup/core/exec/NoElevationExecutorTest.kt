package ca.devdcs.backup.core.exec

import ca.devdcs.backup.core.exec.standard.NoElevationExecutor
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoElevationExecutorTest {

    private val executor = NoElevationExecutor()

    @Test
    fun `reports NONE tier and is always available`() = runTest {
        assertEquals(ElevationTier.NONE, executor.tier)
        assertTrue(executor.isAvailable())
    }

    @Test
    fun `execute refuses every command without launching`() = runTest {
        val result = executor.execute(Command(listOf("id")))

        assertFalse(result.launched)
        assertFalse(result.isSuccess)
        assertEquals(CommandResult.EXIT_NOT_LAUNCHED, result.exitCode)
        assertEquals(
            listOf("No elevated shell in Standard mode; use in-process file APIs"),
            result.stderr,
        )
    }

    @Test
    fun `stream emits a single not-launched exit`() = runTest {
        val events = executor.stream(Command(listOf("tar", "-c", "."))).toList()

        assertEquals(
            listOf(CommandEvent.Exit(CommandResult.EXIT_NOT_LAUNCHED, 0)),
            events,
        )
    }
}
