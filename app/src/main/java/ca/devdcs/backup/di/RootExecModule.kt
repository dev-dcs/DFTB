package ca.devdcs.backup.di

import ca.devdcs.backup.core.exec.CommandExecutor
import ca.devdcs.backup.core.exec.root.RootCommandExecutor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes the Root-tier executor into the multibound `Set<CommandExecutor>` the router collects.
 * Kept in its own module (rather than the shared [RepositoryModule]) so the Root tier's Hilt wiring
 * lives beside the Root tier's code and each elevation tier can be reasoned about in isolation.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RootExecModule {

    @Binds
    @IntoSet
    abstract fun bindRootCommandExecutor(executor: RootCommandExecutor): CommandExecutor
}
