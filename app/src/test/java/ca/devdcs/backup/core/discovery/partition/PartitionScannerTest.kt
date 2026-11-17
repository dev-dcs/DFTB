package ca.devdcs.backup.core.discovery.partition

import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandEvent
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.exec.FakeExecutor
import ca.devdcs.backup.core.exec.FakePreferencesDataStore
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PartitionScannerTest {

    /** A ROOT executor that answers the scan script with a fixture and counts invocations. */
    private class ScriptedRoot(
        private val available: Boolean,
        private val stdout: List<String>,
    ) : CommandExecutor {
        val commands = mutableListOf<Command>()
        override val tier: ElevationTier = ElevationTier.ROOT
        override suspend fun isAvailable(): Boolean = available
        override suspend fun execute(command: Command): CommandResult {
            commands += command
            return CommandResult(CommandResult.EXIT_OK, stdout, emptyList(), durationMillis = 5)
        }
        override fun stream(command: Command): Flow<CommandEvent> = emptyFlow()
    }

    private suspend fun TestScope.withScanner(
        mode: OperatingMode,
        root: ScriptedRoot,
        block: suspend (PartitionScanner) -> Unit,
    ) {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val repo = AppPreferencesRepository(FakePreferencesDataStore())
            repo.setOperatingMode(mode)
            val router = CommandExecutorRouter(setOf(FakeExecutor(ElevationTier.NONE), root), repo, scope)
            block(PartitionScanner(router, UnconfinedTestDispatcher(testScheduler)))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `standard mode is unavailable with root required`() = runTest {
        val root = ScriptedRoot(available = true, stdout = PartitionFixtures.pixel8)
        withScanner(OperatingMode.STANDARD, root) { scanner ->
            val outcome = scanner.scan()
            assertEquals(PartitionUnavailableReason.ROOT_REQUIRED, (outcome as PartitionScanUnavailable).reason)
            assertTrue(root.commands.isEmpty())
        }
    }

    @Test
    fun `root mode without grant is unavailable`() = runTest {
        val root = ScriptedRoot(available = false, stdout = PartitionFixtures.pixel8)
        withScanner(OperatingMode.ROOT, root) { scanner ->
            assertEquals(PartitionUnavailableReason.ROOT_NOT_GRANTED, (scanner.scan() as PartitionScanUnavailable).reason)
        }
    }

    @Test
    fun `root scan runs one script, caches, and rescan forces`() = runTest {
        val root = ScriptedRoot(available = true, stdout = PartitionFixtures.pixel8)
        withScanner(OperatingMode.ROOT, root) { scanner ->
            val first = scanner.scan() as PartitionScanResult
            assertEquals(PartitionSlot.A, first.activeSlot)
            assertEquals(1, root.commands.size)
            assertEquals(listOf("sh", "-c", PartitionScanScript.build()), root.commands.single().argv)

            assertTrue(scanner.scan() === first)
            assertEquals(1, root.commands.size)
            assertTrue(scanner.lastScan?.outcome === first)

            scanner.rescan()
            assertEquals(2, root.commands.size)
        }
    }
}
