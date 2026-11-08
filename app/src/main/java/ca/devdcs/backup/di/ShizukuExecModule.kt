package ca.devdcs.backup.di

import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.shizuku.ShizukuCommandExecutor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes the [ShizukuCommandExecutor] into the `Set<CommandExecutor>` multibinding that
 * [ca.devdcs.backup.core.exec.CommandExecutorRouter] indexes by tier. Kept in the Shizuku feature's
 * own module (rather than the always-present [ExecModule]) so a build that excludes Tier 2 simply
 * drops this file and the router falls back to the NONE executor with no other change.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShizukuExecModule {

    @Binds
    @IntoSet
    abstract fun bindShizukuExecutor(impl: ShizukuCommandExecutor): CommandExecutor
}
