package ca.devdcs.backup.di

import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.ftp.FtpBackend
import ca.devdcs.backup.core.storage.webdav.WebDavBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes the FTP/FTPS and WebDAV backends to the `Set<StorageBackend>` that the storage
 * registry indexes by destination type. Exists so these protocols plug in without the registry
 * enumerating them; other backends contribute from their own modules.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkStorageModule {

    @Binds
    @IntoSet
    abstract fun bindFtpBackend(impl: FtpBackend): StorageBackend

    @Binds
    @IntoSet
    abstract fun bindWebDavBackend(impl: WebDavBackend): StorageBackend
}
