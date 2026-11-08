package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandEvent
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.exec.FakePreferencesDataStore
import ca.devdcs.backup.core.mode.OperatingMode
import ca.devdcs.backup.core.preferences.AppPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegedFindWalkerTest {

    /** Scripted ROOT executor: answers the probes and streams canned find output. */
    private class ScriptedExecutor(
        private val printfSupported: Boolean,
        private val stdout: (Command) -> Sequence<String>,
        private val stderr: List<String> = emptyList(),
        private val exitCode: Int = 0,
    ) : CommandExecutor {
        override val tier = ElevationTier.ROOT
        val streamed = mutableListOf<Command>()
        var streamCancelled = false

        override suspend fun isAvailable() = true

        override suspend fun execute(command: Command): CommandResult = when (command.argv.first()) {
            "find" -> if (printfSupported) {
                CommandResult(0, listOf("${FindCommandBuilder.PROBE_MARKER} 4096 1700000000.0000000000"), emptyList(), 1)
            } else {
                CommandResult(1, emptyList(), listOf("find: Unknown option '-printf'"), 1)
            }
            "stat" -> CommandResult(0, listOf("4096 1 /"), emptyList(), 1)
            else -> CommandResult.notLaunched("unexpected")
        }

        override fun stream(command: Command): Flow<CommandEvent> = flow {
            streamed += command
            for (line in stdout(command)) emit(CommandEvent.Stdout(line))
            for (line in stderr) emit(CommandEvent.Stderr(line))
            emit(CommandEvent.Exit(exitCode, 1))
        }.onCompletion { cause -> if (cause != null) streamCancelled = true }
    }

    private suspend fun walker(executor: CommandExecutor, scope: CoroutineScope, dispatcher: kotlinx.coroutines.CoroutineDispatcher): PrivilegedFindWalker {
        val repo = AppPreferencesRepository(FakePreferencesDataStore())
        repo.setOperatingMode(OperatingMode.ROOT)
        val router = CommandExecutorRouter(setOf(executor), repo, scope)
        return PrivilegedFindWalker(router, dispatcher)
    }

    private val spec = WalkSpec("/data/media/0", recursive = true, exclusions = ExclusionMatcher.DEFAULT)

    @Test
    fun `printf dialect streams parsed events and unreadable entries`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        try {
            val exec = ScriptedExecutor(
                printfSupported = true,
                stdout = { sequenceOf("D /data/media/0/DCIM", "F 12 1700000000.5 /data/media/0/DCIM/a b.jpg", "garbage") },
                stderr = listOf("find: '/data/media/0/locked': Permission denied"),
                exitCode = 1,
            )
            val events = walker(exec, scope, dispatcher).walk(spec).toList()
            assertEquals(
                listOf(
                    WalkEvent.Directory("DCIM"),
                    WalkEvent.File("DCIM/a b.jpg", "/data/media/0/DCIM/a b.jpg", 12, 1_700_000_000_500),
                    WalkEvent.Unreadable("/data/media/0/locked", "Permission denied"),
                    WalkEvent.Directory(""),
                    WalkEvent.Warning(ScanWarning.UNPARSEABLE_OUTPUT, "1 unparseable find line(s)"),
                ),
                events,
            )
            val argv = exec.streamed.single().argv
            assertTrue("-printf" in argv && "-prune" in argv && argv[1] == "/data/media/0/")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `falls back to exec stat and retries a file root`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        try {
            var calls = 0
            val exec = object : CommandExecutor {
                override val tier = ElevationTier.ROOT
                val argvs = mutableListOf<List<String>>()
                override suspend fun isAvailable() = true
                override suspend fun execute(command: Command) = if (command.argv.first() == "stat") {
                    CommandResult(0, listOf("ok"), emptyList(), 1)
                } else {
                    CommandResult(1, emptyList(), listOf("bad"), 1)
                }
                override fun stream(command: Command): Flow<CommandEvent> = flow {
                    calls++
                    argvs += command.argv
                    if (calls == 1) {
                        emit(CommandEvent.Stderr("find: /data/x.bin/: Not a directory"))
                        emit(CommandEvent.Exit(1, 1))
                    } else {
                        emit(CommandEvent.Stdout("F 3 1000 /data/x.bin"))
                        emit(CommandEvent.Exit(0, 1))
                    }
                }
            }
            val events = walker(exec, scope, dispatcher).walk(spec.copy(rootLocation = "/data/x.bin")).toList()
            assertEquals(listOf(WalkEvent.File("x.bin", "/data/x.bin", 3, 1_000_000)), events)
            assertEquals(2, calls)
            assertTrue("stat" in exec.argvs[0])
            assertEquals(listOf("find", "/data/x.bin", "-xdev", "-maxdepth", "0"), exec.argvs[1].take(5))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `root failure is reported once`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        try {
            val exec = ScriptedExecutor(
                printfSupported = true,
                stdout = { emptySequence() },
                stderr = listOf("find: '/data/media/0/': No such file or directory"),
                exitCode = 1,
            )
            val events = walker(exec, scope, dispatcher).walk(spec).toList()
            assertEquals(listOf(WalkEvent.RootUnavailable(RootProblem.NOT_FOUND, "No such file or directory")), events)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `cancelling the collector cancels the find stream`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        try {
            val exec = ScriptedExecutor(
                printfSupported = true,
                stdout = { generateSequence(0) { it + 1 }.map { "F 1 1 /data/media/0/f$it" } },
            )
            val files = walker(exec, scope, dispatcher).walk(spec).filterIsInstance<WalkEvent.File>().take(50).toList()
            assertEquals(50, files.size)
            testScheduler.advanceUntilIdle()
            assertTrue(exec.streamCancelled)
        } finally {
            scope.cancel()
        }
    }
}
