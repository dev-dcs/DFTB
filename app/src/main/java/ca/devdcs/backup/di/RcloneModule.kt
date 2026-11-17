package ca.devdcs.backup.di

import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.rclone.RcloneBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes [RcloneBackend] (RCLONE, S3, MEGA destinations) to the `Set<StorageBackend>` the
 * storage registry indexes by type. Kept beside the rclone feature so the backend's wiring can be
 * reasoned about (or dropped) on its own.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RcloneModule {

    @Binds
    @IntoSet
    abstract fun bindRcloneBackend(impl: RcloneBackend): StorageBackend
}
