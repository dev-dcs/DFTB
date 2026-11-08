package ca.devdcs.backup.core.discovery.files

import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandEvent
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandExecutorRouter
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.exec.ElevationTier
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walker for locations only the elevated shell can read (other apps' `Android/data`, `/data`,
 * shared storage without All files access). Runs ONE streamed `find` per root through the current
 * [CommandExecutor] and parses it line by line, so memory stays flat for millions of entries and
 * collection suspends the reader (backpressure). Cancelling the collector cancels
 * [CommandExecutor.stream], which kills the `find` process.
 *
 * The output dialect (`-printf` vs `-exec stat`) is probed once per tier; see [FindDialect].
 */
@Singleton
class PrivilegedFindWalker @Inject constructor(
    private val router: CommandExecutorRouter,
    @IoDispatcher private val io: CoroutineDispatcher,
) : FileWalker {

    override val kind: WalkerKind = WalkerKind.PRIVILEGED_FIND

    // Only successful probes are cached, so a transient shell failure is retried next scan.
    private val dialects = ConcurrentHashMap<ElevationTier, FindDialect>()

    override fun walk(spec: WalkSpec): Flow<WalkEvent> = flow {
        val executor = router.current()
        if (executor.tier == ElevationTier.NONE || !executor.isAvailable()) {
            emit(WalkEvent.RootUnavailable(RootProblem.TOOL_FAILED, "No privileged shell is available in the current mode"))
            return@flow
        }
        val dialect = dialectFor(executor)
        if (dialect == null) {
            emit(WalkEvent.Warning(ScanWarning.FIND_UNSUPPORTED, "find -printf and find -exec stat both failed"))
            emit(WalkEvent.RootUnavailable(RootProblem.TOOL_FAILED, "find is not usable in the ${executor.tier} shell"))
            return@flow
        }
        val outcome = runFind(executor, spec, dialect, singleFile = false)
        if (outcome == RunOutcome.ROOT_IS_FILE) runFind(executor, spec, dialect, singleFile = true)
    }.flowOn(io)

    private enum class RunOutcome { DONE, ROOT_IS_FILE }

    private suspend fun FlowCollector<WalkEvent>.runFind(
        executor: CommandExecutor,
        spec: WalkSpec,
        dialect: FindDialect,
        singleFile: Boolean,
    ): RunOutcome {
        val excluding = spec.exclusions.enabled
        val argv = FindCommandBuilder.build(
            root = spec.rootLocation,
            recursive = spec.recursive,
            dialect = dialect,
            singleFile = singleFile,
            pruneDirNames = if (excluding) FindCommandBuilder.defaultPruneNames() else emptyList(),
            prunePaths = if (excluding) FindCommandBuilder.defaultPrunePaths(spec.rootLocation) else emptyList(),
        )
        val parser = FindOutputParser(spec.rootLocation, singleFile)
        var rootIsFile = false
        var rootFailed = false
        var sawErrors = false
        var unparseable = 0L
        var exitCode = CommandResult.EXIT_OK
        executor.stream(Command(argv)).collect { event ->
            when (event) {
                is CommandEvent.Stdout -> when (val line = parser.parseStdout(event.line)) {
                    is FindLine.Directory -> emit(WalkEvent.Directory(line.relativePath))
                    is FindLine.File -> emit(WalkEvent.File(line.relativePath, line.absolutePath, line.sizeBytes, line.modifiedAtMillis))
                    is FindLine.Unparseable -> unparseable++
                    is FindLine.Error, FindLine.Ignored -> Unit
                }
                is CommandEvent.Stderr -> when (val line = parser.parseStderr(event.line)) {
                    is FindLine.Error -> {
                        sawErrors = true
                        when {
                            line.isRoot && line.problem == RootProblem.NOT_A_DIRECTORY && !singleFile -> rootIsFile = true
                            line.isRoot && !rootFailed -> {
                                rootFailed = true
                                emit(WalkEvent.RootUnavailable(line.problem, line.message))
                            }
                            line.isRoot -> Unit
                            else -> emit(WalkEvent.Unreadable(line.path ?: spec.rootLocation, line.message))
                        }
                    }
                    else -> Unit
                }
                is CommandEvent.Exit -> exitCode = event.exitCode
            }
        }
        if (rootIsFile) return RunOutcome.ROOT_IS_FILE
        if (!singleFile && !rootFailed) emit(WalkEvent.Directory(""))
        if (unparseable > 0) emit(WalkEvent.Warning(ScanWarning.UNPARSEABLE_OUTPUT, "$unparseable unparseable find line(s)"))
        when {
            exitCode == CommandResult.EXIT_NOT_LAUNCHED && !rootFailed ->
                emit(WalkEvent.RootUnavailable(RootProblem.TOOL_FAILED, "find could not be launched"))
            // Exit 1 with stderr diagnostics is find's normal "some entries unreadable" status.
            exitCode != CommandResult.EXIT_OK && !sawErrors && !rootFailed ->
                emit(WalkEvent.Warning(ScanWarning.FIND_EXIT_NONZERO, "find exited with $exitCode"))
        }
        return RunOutcome.DONE
    }

    private suspend fun dialectFor(executor: CommandExecutor): FindDialect? {
        dialects[executor.tier]?.let { return it }
        val printf = executor.execute(Command(FindCommandBuilder.probeCommand(), timeoutMillis = PROBE_TIMEOUT_MS))
        val dialect = when {
            printf.isSuccess && printf.stdout.any(FindCommandBuilder::isProbeOutputValid) -> FindDialect.PRINTF
            executor.execute(Command(listOf("stat", "-c", "%s %Y %n", "/"), timeoutMillis = PROBE_TIMEOUT_MS)).isSuccess -> FindDialect.EXEC_STAT
            else -> null
        }
        if (dialect != null) {
            dialects[executor.tier] = dialect
            DftbLog.i(TAG, "find dialect for ${executor.tier}: $dialect")
        } else {
            DftbLog.w(TAG, "No usable find dialect for ${executor.tier}: ${printf.stderrText}")
        }
        return dialect
    }

    private companion object {
        const val TAG = "FindWalker"
        const val PROBE_TIMEOUT_MS = 10_000L
    }
}
