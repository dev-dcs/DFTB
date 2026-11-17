package ca.devdcs.backup.core.pipeline.crypto

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.EncryptionHeader
import ca.devdcs.backup.core.pipeline.StreamCipher
import ca.devdcs.backup.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tunables for new streams. Old streams are always decrypted with the parameters in their own
 * header, so these can change between releases without breaking restores.
 */
data class StreamCipherSettings(
    val argon2: Argon2Settings = Argon2Settings(),
    val chunkSize: Int = StreamHeader.DEFAULT_CHUNK,
    val saltBytes: Int = StreamHeader.DEFAULT_SALT,
) {
    init {
        require(chunkSize in StreamHeader.MIN_CHUNK..StreamHeader.MAX_CHUNK) { "chunkSize out of range" }
        require(saltBytes in StreamHeader.MIN_SALT..StreamHeader.MAX_SALT) { "saltBytes out of range" }
    }
}

/**
 * The `dftb-aesgcm-v1` [StreamCipher]: a [StreamHeader] followed by AES-256-GCM chunks.
 *
 * Key schedule: master = Argon2id(passphrase, kdfSalt) or the 32-byte vault key; then
 * HKDF-SHA256(master, streamSalt) yields a per-stream AES key ("stream-key") and a 16-byte key
 * check ("key-check"). The master (and kdfSalt) may be shared by every stream of a job through a
 * [PreparedKey], so Argon2 runs once per job; streamSalt is always fresh random per stream, so the
 * AES key is unique per stream and the nonce `noncePrefix || u64 chunkIndex` never repeats under
 * one key.
 *
 * Chunk i is sealed with AAD = SHA-256(header) || u64 i || u8 final. Every non-final chunk carries
 * exactly `chunkSize` plaintext bytes and the final chunk strictly fewer (possibly zero), so the
 * reader knows from the length alone which chunk must be final: dropping, reordering, swapping,
 * truncating or appending chunks all fail authentication or the end-of-stream check.
 *
 * The caller's passphrase [CharArray] is not modified (the caller owns and wipes it); every
 * derived byte array is wiped as soon as it has been turned into a JCA key.
 */
@Singleton
class AesGcmStreamCipher internal constructor(
    private val keys: VaultKeyStore,
    private val io: CoroutineDispatcher,
    val settings: StreamCipherSettings,
) : StreamCipher {

    @Inject
    constructor(keys: VaultKeyStore, @IoDispatcher io: CoroutineDispatcher) : this(keys, io, StreamCipherSettings())

    /** Same cipher with different parameters for new streams (e.g. lower Argon2 memory on low-RAM devices). */
    fun withSettings(settings: StreamCipherSettings): AesGcmStreamCipher = AesGcmStreamCipher(keys, io, settings)

    /** Generates and stores a new random vault key; use the returned ref in [CipherKeySpec.VaultKey]. */
    suspend fun createVaultKey(): String = keys.createVaultKey()

    /**
     * Runs the KDF once (fresh random Argon2 salt with [settings]) or loads the vault key, for
     * encrypting many streams of one job. Close the result when the job ends.
     */
    suspend fun prepare(key: CipherKeySpec): PreparedKey = withContext(io) {
        val kdf = when (key) {
            is CipherKeySpec.Passphrase -> StreamHeader.Kdf.Argon2id(settings.argon2, Secrets.randomBytes(settings.saltBytes))
            is CipherKeySpec.VaultKey -> StreamHeader.Kdf.Vault(key.vaultRef)
        }
        PreparedKey(masterKey(keys, key, kdf), kdf)
    }

    /** One-off stream: equivalent to [prepare] + [encrypt] (PreparedKey) + close. */
    override suspend fun encrypt(out: OutputStream, key: CipherKeySpec): EncryptingOutputStream =
        prepare(key).use { encrypt(out, it) }

    /** New stream under a job-level key; per-stream random HKDF salt and nonce prefix. */
    suspend fun encrypt(out: OutputStream, key: PreparedKey): EncryptingOutputStream = withContext(io) {
        val streamSalt = Secrets.randomBytes(settings.saltBytes)
        val derived = key.withMaster { deriveFromMaster(it, streamSalt) }
        val header = StreamHeader(
            version = StreamHeader.VERSION,
            kdf = key.kdf,
            salt = streamSalt,
            noncePrefix = Secrets.randomBytes(StreamHeader.NONCE_PREFIX_BYTES),
            chunkSize = settings.chunkSize,
            keyCheck = derived.keyCheck,
        )
        val bytes = header.encode()
        try {
            out.write(bytes)
        } catch (e: Throwable) {
            derived.streamKey.fill(0)
            throw e
        }
        EncryptingOutputStream(out, derived.streamKey, StreamHeader.Encoded(header, bytes))
    }

    override suspend fun decrypt(input: InputStream, key: CipherKeySpec): DecryptingInputStream =
        DecryptKeyCache(key, capacity = 1).use { decrypt(input, it) }

    /** Decrypts with masters cached in [session], so restoring many files of one job derives once. */
    suspend fun decrypt(input: InputStream, session: DecryptKeyCache): DecryptingInputStream = withContext(io) {
        val encoded = StreamHeader.read(input)
        val header = encoded.header
        val typeMatches = when (session.key) {
            is CipherKeySpec.Passphrase -> header.kdf is StreamHeader.Kdf.Argon2id
            is CipherKeySpec.VaultKey -> header.kdf is StreamHeader.Kdf.Vault
        }
        if (!typeMatches) {
            throw CipherIntegrityException(CipherIntegrityException.Reason.KEY_TYPE_MISMATCH, "Stream was encrypted with a different key type")
        }
        val derived = session.withMaster(header.kdf, { masterKey(keys, session.key, header.kdf) }) { deriveFromMaster(it, header.salt) }
        if (!MessageDigest.isEqual(derived.keyCheck, header.keyCheck)) {
            derived.streamKey.fill(0)
            throw CipherIntegrityException(CipherIntegrityException.Reason.WRONG_KEY, "Wrong passphrase or key")
        }
        DecryptingInputStream(input, derived.streamKey, encoded)
    }

    /** What [encrypt] will record (without the Argon2 salt, which is chosen per [prepare]). */
    override suspend fun describe(key: CipherKeySpec): EncryptionHeader =
        EncryptionHeader(StreamHeader.SCHEME, StreamHeader.describeParams(kdfFor(key), settings.chunkSize, includeKdfSalt = false))

    /** Exact job-level description incl. the Argon2 salt; equals each stream's [StreamHeader.toEncryptionHeader]. */
    fun describe(key: PreparedKey): EncryptionHeader =
        EncryptionHeader(StreamHeader.SCHEME, StreamHeader.describeParams(key.kdf, settings.chunkSize))

    /** Reads only the header of [input] (blocking; on IO) to learn its parameters without a key. */
    suspend fun inspect(input: InputStream): StreamHeader = withContext(io) { StreamHeader.read(input).header }

    private fun kdfFor(key: CipherKeySpec): StreamHeader.Kdf = when (key) {
        // Placeholder salt only feeds describe(), which omits it.
        is CipherKeySpec.Passphrase -> StreamHeader.Kdf.Argon2id(settings.argon2, ByteArray(settings.saltBytes))
        is CipherKeySpec.VaultKey -> StreamHeader.Kdf.Vault(key.vaultRef)
    }

    internal class DerivedKeys(val streamKey: ByteArray, val keyCheck: ByteArray)

    internal companion object {
        val INFO_STREAM_KEY = "dftb-aesgcm-v1/stream-key".toByteArray(Charsets.US_ASCII)
        val INFO_KEY_CHECK = "dftb-aesgcm-v1/key-check".toByteArray(Charsets.US_ASCII)

        fun deriveFromMaster(master: ByteArray, streamSalt: ByteArray) = DerivedKeys(
            streamKey = Hkdf.sha256(master, streamSalt, INFO_STREAM_KEY, 32),
            keyCheck = Hkdf.sha256(master, streamSalt, INFO_KEY_CHECK, StreamHeader.KEY_CHECK_BYTES),
        )

        /** Master key for [key]; for passphrases [kdf] must be Argon2id (cost + salt). Caller wipes. */
        suspend fun masterKey(keys: VaultKeyStore, key: CipherKeySpec, kdf: StreamHeader.Kdf): ByteArray =
            when (key) {
                is CipherKeySpec.Passphrase -> {
                    val argon = kdf as? StreamHeader.Kdf.Argon2id
                        ?: throw CipherIntegrityException(CipherIntegrityException.Reason.KEY_TYPE_MISMATCH, "Passphrase given for a vault-key stream")
                    val settings = argon.settings
                    // Refuse costs this heap cannot hold instead of letting OutOfMemoryError kill the worker.
                    val needBytes = settings.memoryKiB.toLong() * 1024
                    if (needBytes > Runtime.getRuntime().maxMemory() / 2) {
                        throw CipherIntegrityException(CipherIntegrityException.Reason.INSUFFICIENT_MEMORY, "Argon2 memory cost exceeds available heap")
                    }
                    try {
                        Argon2Kdf.deriveFromPassphrase(key.chars, argon.kdfSalt, settings)
                    } catch (e: OutOfMemoryError) {
                        throw CipherIntegrityException(CipherIntegrityException.Reason.INSUFFICIENT_MEMORY, "Out of memory during Argon2", e)
                    }
                }
                is CipherKeySpec.VaultKey -> keys.loadKey(key.vaultRef)
            }
    }
}
