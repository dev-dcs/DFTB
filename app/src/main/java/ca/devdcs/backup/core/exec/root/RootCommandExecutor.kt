package ca.devdcs.backup.core.exec.root

import ca.devdcs.backup.core.exec.Command
import ca.devdcs.backup.core.exec.CommandEvent
import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.CommandResult
import ca.devdcs.backup.core.exec.ElevationTier
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The [ElevationTier.ROOT] [CommandExecutor]: the entry point the router hands commands to when the
 * device owner runs in Root or Root+Module mode. It is a thin adapter over [RootShellManager] so the
 * shell lifecycle, quoting and timeout logic live in exactly one place and stay identical whether a
 * command is buffered ([execute]) or streamed ([stream]).
 */
@Singleton
class RootCommandExecutor @Inject constructor(
    private val shellManager: RootShellManager,
) : CommandExecutor {

    override val tier: ElevationTier = ElevationTier.ROOT

    override suspend fun isAvailable(): Boolean = shellManager.isRootAvailable()

    override suspend fun execute(command: Command): CommandResult = shellManager.exec(command)

    override fun stream(command: Command): Flow<CommandEvent> = shellManager.stream(command)
}
