package ca.devdcs.backup.di

import ca.devdcs.backup.core.pipeline.ChecksumEngine
import ca.devdcs.backup.core.pipeline.StreamCipher
import ca.devdcs.backup.core.pipeline.checksum.DefaultChecksumEngine
import ca.devdcs.backup.core.pipeline.crypto.AesGcmStreamCipher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the pipeline's checksum and encryption contracts to their single implementations, so the
 * orchestrator depends only on [ChecksumEngine] / [StreamCipher]. Filename maskers are per-key and
 * come from the injectable `FilenameMaskerFactory` instead of a binding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PipelineCryptoModule {

    @Binds
    abstract fun bindChecksumEngine(impl: DefaultChecksumEngine): ChecksumEngine

    @Binds
    abstract fun bindStreamCipher(impl: AesGcmStreamCipher): StreamCipher
}
