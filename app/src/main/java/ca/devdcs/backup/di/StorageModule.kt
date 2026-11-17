package ca.devdcs.backup.di

import ca.devdcs.backup.core.storage.DefaultStorageRegistry
import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.StorageRegistry
import ca.devdcs.backup.core.storage.local.LocalInternalBackend
import ca.devdcs.backup.core.storage.local.SafBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/**
 * Wires the storage layer: declares the `Set<StorageBackend>` multibinding, binds
 * [StorageRegistry], and contributes the local backends.
 *
 * Network/cloud backends contribute to the SAME set from their own modules with
 * `@Binds @IntoSet abstract fun bindX(impl: XBackend): StorageBackend`; [Multibinds] keeps the
 * graph valid in builds that ship none of them.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Multibinds
    abstract fun storageBackends(): Set<StorageBackend>

    @Binds
    abstract fun bindStorageRegistry(impl: DefaultStorageRegistry): StorageRegistry

    @Binds
    @IntoSet
    abstract fun bindLocalInternalBackend(impl: LocalInternalBackend): StorageBackend

    @Binds
    @IntoSet
    abstract fun bindSafBackend(impl: SafBackend): StorageBackend
}
