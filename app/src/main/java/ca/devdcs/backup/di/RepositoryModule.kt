package ca.devdcs.backup.di

import ca.devdcs.backup.data.repository.CheckpointRepositoryImpl
import ca.devdcs.backup.data.repository.DestinationRepositoryImpl
import ca.devdcs.backup.data.repository.HashRegistryRepositoryImpl
import ca.devdcs.backup.data.repository.HistoryRepositoryImpl
import ca.devdcs.backup.data.repository.ProfileRepositoryImpl
import ca.devdcs.backup.domain.repository.CheckpointRepository
import ca.devdcs.backup.domain.repository.DestinationRepository
import ca.devdcs.backup.domain.repository.HashRegistryRepository
import ca.devdcs.backup.domain.repository.HistoryRepository
import ca.devdcs.backup.domain.repository.ProfileRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds each domain repository interface to its Room implementation. Callers inject the interface
 * only, so tests and future storage backends can replace a binding without touching call sites.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    @Singleton
    abstract fun bindDestinationRepository(impl: DestinationRepositoryImpl): DestinationRepository

    @Binds
    @Singleton
    abstract fun bindHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository

    @Binds
    @Singleton
    abstract fun bindCheckpointRepository(impl: CheckpointRepositoryImpl): CheckpointRepository

    @Binds
    @Singleton
    abstract fun bindHashRegistryRepository(impl: HashRegistryRepositoryImpl): HashRegistryRepository
}
