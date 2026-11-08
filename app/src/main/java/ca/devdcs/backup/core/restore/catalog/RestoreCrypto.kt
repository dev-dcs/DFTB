package ca.devdcs.backup.core.restore.catalog

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.FilenameMasker
import ca.devdcs.backup.core.pipeline.crypto.AesGcmStreamCipher
import ca.devdcs.backup.core.pipeline.crypto.DecryptKeyCache
import ca.devdcs.backup.core.pipeline.crypto.FilenameMaskerFactory
import ca.devdcs.backup.core.pipeline.crypto.MaskerParams
import ca.devdcs.backup.core.pipeline.crypto.StreamHeader
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decrypts every stream of one opened backup with a shared key cache, so a 10k-file tree runs the
 * passphrase KDF once. [close] wipes the cached masters.
 */
interface RestoreKeySession : AutoCloseable {
    /** Decrypting wrapper; wrong keys and tampering surface as CipherIntegrityException. */
    suspend fun decrypt(input: InputStream): InputStream
    override fun close()
}

/** A filename masker rebuilt from a manifest's public parameters; [close] wipes its keys. */
class MaskerHandle(val masker: FilenameMasker, private val onClose: () -> Unit) : AutoCloseable {
    override fun close() = onClose()
}

/**
 * The catalog's crypto seam. Exists so the catalog is JVM-testable with a fake cipher while the
 * app uses the real `dftb-aesgcm-v1` cipher and SIV masker.
 */
interface RestoreCrypto {
    /** True when artifacts encrypted with [scheme] can be decrypted by this build. */
    fun supports(scheme: String): Boolean

    fun session(key: CipherKeySpec): RestoreKeySession

    /** Masker for [params] (manifest `masking`), or null when the parameters are not understood. */
    suspend fun openMasker(key: CipherKeySpec, params: Map<String, String>): MaskerHandle?
}

/** [RestoreCrypto] over [AesGcmStreamCipher] + [DecryptKeyCache] and [FilenameMaskerFactory]. */
@Singleton
class AesGcmRestoreCrypto @Inject constructor(
    private val cipher: AesGcmStreamCipher,
    private val maskers: FilenameMaskerFactory,
) : RestoreCrypto {

    override fun supports(scheme: String): Boolean = scheme == StreamHeader.SCHEME

    override fun session(key: CipherKeySpec): RestoreKeySession {
        val cache = DecryptKeyCache(key)
        return object : RestoreKeySession {
            override suspend fun decrypt(input: InputStream): InputStream = cipher.decrypt(input, cache)
            override fun close() = cache.close()
        }
    }

    override suspend fun openMasker(key: CipherKeySpec, params: Map<String, String>): MaskerHandle? {
        val parsed = MaskerParams.fromMap(params) ?: return null
        val masker = maskers.create(key, parsed)
        return MaskerHandle(masker, masker::close)
    }
}
