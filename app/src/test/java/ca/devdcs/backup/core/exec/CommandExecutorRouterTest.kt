package ca.devdcs.backup.core.exec

import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandExecutorRouterTest {

    private val none = FakeExecutor(ElevationTier.NONE)
    private val shizuku = FakeExecutor(ElevationTier.SHIZUKU)
    private val root = FakeExecutor(ElevationTier.ROOT)

    private fun prepareRouter(executors: Set<CommandExecutor>): Pair<AppPreferencesRepository, (CoroutineScope) -> CommandExecutorRouter> {
        val repo = AppPreferencesRepository(FakePreferencesDataStore())
        return repo to { scope -> CommandExecutorRouter(executors, repo, scope) }
    }

    @Test
    fun `routes each operating mode to the matching tier`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val (repo, build) = prepareRouter(setOf(none, shizuku, root))
            val router = build(scope)

            repo.setOperatingMode(OperatingMode.STANDARD)
            assertSame(none, router.current())

            repo.setOperatingMode(OperatingMode.SHIZUKU)
            assertSame(shizuku, router.current())

            repo.setOperatingMode(OperatingMode.ROOT)
            assertSame(root, router.current())

            // ROOT_MODULE shares the ROOT tier.
            repo.setOperatingMode(OperatingMode.ROOT_MODULE)
            assertSame(root, router.current())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `unset mode routes to NONE`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val (_, build) = prepareRouter(setOf(none, shizuku, root))
            val router = build(scope)
            // No setOperatingMode call: operatingMode is null -> STANDARD -> NONE.
            assertSame(none, router.current())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `falls back to NONE when the mapped tier has no implementation`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            // Root-only build: Shizuku executor is not registered.
            val (repo, build) = prepareRouter(setOf(none, root))
            val router = build(scope)

            repo.setOperatingMode(OperatingMode.SHIZUKU)
            assertSame(none, router.current())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `executorFor returns the tier impl or null`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val (_, build) = prepareRouter(setOf(none, root))
            val router = build(scope)

            assertSame(none, router.executorFor(ElevationTier.NONE))
            assertSame(root, router.executorFor(ElevationTier.ROOT))
            assertNull(router.executorFor(ElevationTier.SHIZUKU))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `require returns the tier impl`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val (_, build) = prepareRouter(setOf(none, root))
            val router = build(scope)
            assertSame(root, router.require(ElevationTier.ROOT))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `require throws CommandException for an absent tier`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val (_, build) = prepareRouter(setOf(none, root))
            val router = build(scope)
            try {
                router.require(ElevationTier.SHIZUKU)
                fail("expected CommandException")
            } catch (expected: CommandException) {
                assertTrue(expected.message!!.contains("SHIZUKU"))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `execute delegates to the current mode executor`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val (repo, build) = prepareRouter(setOf(none, shizuku, root))
            val router = build(scope)
            repo.setOperatingMode(OperatingMode.ROOT)

            val result = router.execute(Command(listOf("id")))

            assertEquals(listOf(ElevationTier.ROOT.name), result.stdout)
            assertEquals(1, root.executed.size)
            assertEquals(0, shizuku.executed.size)
            assertEquals(0, none.executed.size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stream resolves the current executor when collected`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val (repo, build) = prepareRouter(setOf(none, shizuku, root))
            val router = build(scope)
            repo.setOperatingMode(OperatingMode.SHIZUKU)

            val events = router.stream(Command(listOf("tar", "-c", "."))).toList()

            assertEquals(
                listOf(
                    CommandEvent.Stdout(ElevationTier.SHIZUKU.name),
                    CommandEvent.Exit(CommandResult.EXIT_OK, 1),
                ),
                events,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `bestAvailable picks the highest privilege available executor`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val (_, build) = prepareRouter(setOf(none, shizuku, root))
            val router = build(scope)
            // All available -> ROOT wins regardless of selected mode.
            assertSame(root, router.bestAvailable())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `bestAvailable skips unavailable higher tiers`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val rootDown = FakeExecutor(ElevationTier.ROOT, available = false)
            val shizukuUp = FakeExecutor(ElevationTier.SHIZUKU, available = true)
            val (_, build) = prepareRouter(setOf(none, shizukuUp, rootDown))
            val router = build(scope)
            assertSame(shizukuUp, router.bestAvailable())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `bestAvailable returns NONE when no elevated tier is available`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val rootDown = FakeExecutor(ElevationTier.ROOT, available = false)
            val shizukuDown = FakeExecutor(ElevationTier.SHIZUKU, available = false)
            val (_, build) = prepareRouter(setOf(none, shizukuDown, rootDown))
            val router = build(scope)
            assertSame(none, router.bestAvailable())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `activeTier reflects the persisted operating mode`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val (repo, build) = prepareRouter(setOf(none, shizuku, root))
            val router = build(scope)

            assertEquals(ElevationTier.NONE, router.activeTier.value)

            repo.setOperatingMode(OperatingMode.ROOT)
            assertEquals(ElevationTier.ROOT, router.activeTier.value)

            repo.setOperatingMode(OperatingMode.SHIZUKU)
            assertEquals(ElevationTier.SHIZUKU, router.activeTier.value)
        } finally {
            scope.cancel()
        }
    }
}
