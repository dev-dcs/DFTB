package ca.devdcs.backup.di

import ca.devdcs.backup.data.security.FileCredentialVault
import ca.devdcs.backup.data.security.KeystoreCipher
import ca.devdcs.backup.data.security.VaultCipher
import ca.devdcs.backup.domain.security.CredentialVault
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Wires the credential vault: one Keystore-backed cipher for the process, one file vault on top of
 * it. Consumers depend on the [CredentialVault] interface only, so tests and future backends
 * (e.g. a hardware token) swap in without touching callers.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindCredentialVault(impl: FileCredentialVault): CredentialVault

    @Binds
    abstract fun bindVaultCipher(impl: KeystoreCipher): VaultCipher

    companion object {
        @Provides
        @Singleton
        fun provideKeystoreCipher(): KeystoreCipher = KeystoreCipher()
    }
}
