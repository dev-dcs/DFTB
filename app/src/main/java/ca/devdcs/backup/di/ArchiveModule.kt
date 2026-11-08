package ca.devdcs.backup.di

import ca.devdcs.backup.core.pipeline.ArchiveFactory
import ca.devdcs.backup.core.pipeline.archive.ArchiveReaderFactory
import ca.devdcs.backup.core.pipeline.archive.DefaultArchiveFactory
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the archive layer: one [DefaultArchiveFactory] singleton serves both the pipeline's
 * [ArchiveFactory] contract and restore's [ArchiveReaderFactory].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ArchiveModule {

    @Binds
    abstract fun bindArchiveFactory(impl: DefaultArchiveFactory): ArchiveFactory

    @Binds
    abstract fun bindArchiveReaderFactory(impl: DefaultArchiveFactory): ArchiveReaderFactory
}
