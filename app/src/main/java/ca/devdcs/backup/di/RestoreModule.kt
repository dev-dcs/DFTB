package ca.devdcs.backup.di

import ca.devdcs.backup.core.restore.BackupCatalog
import ca.devdcs.backup.core.restore.apps.DefaultAppRestorer
import ca.devdcs.backup.core.restore.catalog.AesGcmRestoreCrypto
import ca.devdcs.backup.core.restore.catalog.BackupItemReader
import ca.devdcs.backup.core.restore.catalog.DefaultBackupCatalog
import ca.devdcs.backup.core.restore.catalog.RestoreCrypto
import ca.devdcs.backup.core.restore.engine.AndroidRestoreMessages
import ca.devdcs.backup.core.restore.engine.AppRestorer
import ca.devdcs.backup.core.restore.engine.PartitionRestorer
import ca.devdcs.backup.core.restore.engine.RestoreMessages
import ca.devdcs.backup.core.restore.files.AndroidRestoreTargetOpener
import ca.devdcs.backup.core.restore.files.RestoreTargetOpener
import ca.devdcs.backup.core.restore.partition.RootPartitionRestorer
import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the restore layer: one [DefaultBackupCatalog] serves both the catalog contract and
 * per-item tree reads; the crypto, messages and target seams get their Android implementations.
 * [AppRestorer] / [PartitionRestorer] are bound to the default implementations and also declared
 * optional, so the engine (which injects `Optional<…>`) keeps working if a build drops one: those
 * units are then reported unavailable instead of failing the graph.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RestoreModule {

    @Binds
    abstract fun bindBackupCatalog(impl: DefaultBackupCatalog): BackupCatalog

    @Binds
    abstract fun bindBackupItemReader(impl: DefaultBackupCatalog): BackupItemReader

    @Binds
    abstract fun bindRestoreCrypto(impl: AesGcmRestoreCrypto): RestoreCrypto

    @Binds
    abstract fun bindRestoreMessages(impl: AndroidRestoreMessages): RestoreMessages

    @Binds
    abstract fun bindRestoreTargetOpener(impl: AndroidRestoreTargetOpener): RestoreTargetOpener

    @Binds
    abstract fun bindPartitionRestorer(impl: RootPartitionRestorer): PartitionRestorer

    @Binds
    abstract fun bindAppRestorer(impl: DefaultAppRestorer): AppRestorer

    @BindsOptionalOf
    abstract fun optionalAppRestorer(): AppRestorer

    @BindsOptionalOf
    abstract fun optionalPartitionRestorer(): PartitionRestorer
}
