package ca.devdcs.backup.core.exec

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A pure-JVM [CommandExecutor] double for router tests. It records what it was asked to run and
 * reports a configurable [isAvailable], so tests can assert which executor the router selected
 * without any Android, libsu or Shizuku dependency.
 */
class FakeExecutor(
    override val tier: ElevationTier,
    private val available: Boolean = true,
) : CommandExecutor {

    /** Commands passed to [execute], in order, so a test can confirm delegation happened. */
    val executed = mutableListOf<Command>()

    override suspend fun isAvailable(): Boolean = available

    override suspend fun execute(command: Command): CommandResult {
        executed += command
        // stdout carries the tier name so a test can tell which executor actually ran.
        return CommandResult(CommandResult.EXIT_OK, listOf(tier.name), emptyList(), durationMillis = 1)
    }

    override fun stream(command: Command): Flow<CommandEvent> = flowOf(
        CommandEvent.Stdout(tier.name),
        CommandEvent.Exit(CommandResult.EXIT_OK, durationMillis = 1),
    )
}
