package ca.devdcs.backup.di

import ca.devdcs.backup.core.storage.StorageBackend
import ca.devdcs.backup.core.storage.nfs.NfsBackend
import ca.devdcs.backup.core.storage.smb.SmbBackend
import ca.devdcs.backup.core.storage.ssh.SftpBackend
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Contributes the SFTP/SSH, SMB and NFS backends into the multibound `Set<StorageBackend>` that the
 * storage registry indexes by destination type. Kept beside these backends' own code so the
 * secure-network storage tier can be wired (or dropped from a build) in isolation.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecureNetStorageModule {

    @Binds
    @IntoSet
    abstract fun bindSftpBackend(impl: SftpBackend): StorageBackend

    @Binds
    @IntoSet
    abstract fun bindSmbBackend(impl: SmbBackend): StorageBackend

    @Binds
    @IntoSet
    abstract fun bindNfsBackend(impl: NfsBackend): StorageBackend
}
