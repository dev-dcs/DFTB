package ca.devdcs.backup.di

import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.standard.NoElevationExecutor
import dagger.Binds
import dagger.Module
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Assembles the set of [CommandExecutor] implementations that
 * [ca.devdcs.backup.core.exec.CommandExecutorRouter] indexes by tier.
 *
 * The router injects `Set<CommandExecutor>`; each tier's executor contributes itself with
 * `@Binds @IntoSet` so no central registry has to enumerate them. This module owns only the
 * always-present [NoElevationExecutor] (tier NONE). The Root and Shizuku executors live in their
 * own feature packages and contribute to the SAME set from their OWN `@Module`
 * (`@InstallIn(SingletonComponent::class)`) with their own `@Binds @IntoSet` function, e.g.:
 *
 * ```
 * @Binds @IntoSet
 * abstract fun bindRootExecutor(impl: RootExecutor): CommandExecutor
 * ```
 *
 * [Multibinds] declares the set so the graph still compiles (as a set containing just NONE) in a
 * build that omits the elevated executors entirely.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ExecModule {

    /** Declares the multibinding so `Set<CommandExecutor>` resolves even before any contribution. */
    @Multibinds
    abstract fun executors(): Set<CommandExecutor>

    /** The Standard-mode (NONE tier) executor; always present so the router is never empty. */
    @Binds
    @IntoSet
    abstract fun bindNoElevationExecutor(impl: NoElevationExecutor): CommandExecutor
}
