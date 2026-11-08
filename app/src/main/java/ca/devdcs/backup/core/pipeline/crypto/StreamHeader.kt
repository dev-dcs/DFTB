package ca.devdcs.backup.core.pipeline.crypto

import ca.devdcs.backup.core.pipeline.EncryptionHeader
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Parsed `dftb-aesgcm-v1` stream header. Exists so the encrypting and decrypting sides share one
 * byte layout, and so restore/verify can inspect an artifact's parameters without the key.
 *
 * Layout (big-endian):
 * ```
 * magic 8 "DFTBENC1"
 * version 1 = 1
 * kdfId 1 1 = Argon2id(passphrase), 2 = vault key
 * Argon2id: u32 memoryKiB, u32 iterations, u8 parallelism, u8 kdfSaltLength (16..32), kdfSalt
 * (kdfSalt may be shared by many streams of one job: one Argon2 run per job)
 * vault: u16 refLength, ref (UTF-8, <= 512 bytes; not secret)
 * saltLength 1 16..32
 * salt n per-stream random HKDF salt => a distinct AES key for every stream
 * noncePrefix 4 random; nonce = prefix || u64 chunkIndex
 * chunkSize u32 plaintext bytes per non-final chunk
 * keyCheck 16 HKDF(master, salt, "key-check"): distinguishes a wrong key from tampering
 * ```
 * The SHA-256 of these exact bytes is bound into every chunk's AAD, so no header field can be
 * altered without every chunk failing authentication.
 */
data class StreamHeader(
    val version: Int,
    val kdf: Kdf,
    val salt: ByteArray,
    val noncePrefix: ByteArray,
    val chunkSize: Int,
    val keyCheck: ByteArray,
) {
    /** How the master key is obtained; secrets never appear here. */
    sealed interface Kdf {
        /** [kdfSalt] is the Argon2 salt (job-level); it is not secret. */
        class Argon2id(val settings: Argon2Settings, val kdfSalt: ByteArray) : Kdf {
            init {
                require(kdfSalt.size in MIN_SALT..MAX_SALT) { "KDF salt length out of range" }
            }
            override fun equals(other: Any?): Boolean =
                other is Argon2id && settings == other.settings && kdfSalt.contentEquals(other.kdfSalt)
            override fun hashCode(): Int = settings.hashCode() * 31 + kdfSalt.contentHashCode()
            override fun toString(): String = "Argon2id(settings=$settings)"
        }
        data class Vault(val vaultRef: String) : Kdf
    }

    fun encode(): ByteArray {
        val bos = ByteArrayOutputStream(96)
        DataOutputStream(bos).use { out ->
            out.write(MAGIC)
            out.writeByte(version)
            when (kdf) {
                is Kdf.Argon2id -> {
                    out.writeByte(KDF_ARGON2ID)
                    out.writeInt(kdf.settings.memoryKiB)
                    out.writeInt(kdf.settings.iterations)
                    out.writeByte(kdf.settings.parallelism)
                    out.writeByte(kdf.kdfSalt.size)
                    out.write(kdf.kdfSalt)
                }
                is Kdf.Vault -> {
                    out.writeByte(KDF_VAULT)
                    val ref = kdf.vaultRef.toByteArray(Charsets.UTF_8)
                    require(ref.size in 1..MAX_REF_BYTES) { "Vault ref length out of range" }
                    out.writeShort(ref.size)
                    out.write(ref)
                }
            }
            require(salt.size in MIN_SALT..MAX_SALT) { "Salt length out of range" }
            out.writeByte(salt.size)
            out.write(salt)
            require(noncePrefix.size == NONCE_PREFIX_BYTES)
            out.write(noncePrefix)
            require(chunkSize in MIN_CHUNK..MAX_CHUNK) { "Chunk size out of range" }
            out.writeInt(chunkSize)
            require(keyCheck.size == KEY_CHECK_BYTES)
            out.write(keyCheck)
        }
        return bos.toByteArray()
    }

    /** Manifest-friendly description incl. the (non-secret) Argon2 salt; per-stream HKDF salt/nonce stay in the stream. */
    fun toEncryptionHeader(): EncryptionHeader = EncryptionHeader(SCHEME, describeParams(kdf, chunkSize, version))

    override fun equals(other: Any?): Boolean =
        other is StreamHeader && version == other.version && kdf == other.kdf && chunkSize == other.chunkSize &&
            salt.contentEquals(other.salt) && noncePrefix.contentEquals(other.noncePrefix) && keyCheck.contentEquals(other.keyCheck)

    override fun hashCode(): Int =
        ((version * 31 + kdf.hashCode()) * 31 + chunkSize) * 31 + salt.contentHashCode()

    override fun toString(): String = "StreamHeader(version=$version, kdf=$kdf, chunkSize=$chunkSize)"

    /** Header bytes as read/written plus their SHA-256 (the AAD prefix). */
    class Encoded(val header: StreamHeader, val bytes: ByteArray) {
        val hash: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    companion object {
        const val SCHEME = "dftb-aesgcm-v1"
        val MAGIC: ByteArray = "DFTBENC1".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1
        const val KDF_ARGON2ID = 1
        const val KDF_VAULT = 2
        const val MIN_SALT = 16
        const val MAX_SALT = 32
        const val DEFAULT_SALT = 32
        const val NONCE_PREFIX_BYTES = 4
        const val KEY_CHECK_BYTES = 16
        const val MAX_REF_BYTES = 512
        const val MIN_CHUNK = 4 * 1024
        const val MAX_CHUNK = 16 * 1024 * 1024
        const val DEFAULT_CHUNK = 1024 * 1024

        // Manifest param keys.
        const val P_VERSION = "version"
        const val P_KDF = "kdf"
        const val P_MEMORY_KIB = "argon2.memoryKiB"
        const val P_ITERATIONS = "argon2.iterations"
        const val P_PARALLELISM = "argon2.parallelism"
        const val P_KDF_SALT = "argon2.salt"
        const val P_VAULT_REF = "vaultRef"
        const val P_CHUNK_SIZE = "chunkSize"
        const val P_CIPHER = "cipher"

        internal fun describeParams(kdf: Kdf, chunkSize: Int, version: Int = VERSION, includeKdfSalt: Boolean = true): Map<String, String> {
            val m = linkedMapOf(
                P_VERSION to version.toString(),
                P_CIPHER to "AES-256-GCM",
                P_CHUNK_SIZE to chunkSize.toString(),
            )
            when (kdf) {
                is Kdf.Argon2id -> {
                    m[P_KDF] = "argon2id"
                    m[P_MEMORY_KIB] = kdf.settings.memoryKiB.toString()
                    m[P_ITERATIONS] = kdf.settings.iterations.toString()
                    m[P_PARALLELISM] = kdf.settings.parallelism.toString()
                    if (includeKdfSalt) m[P_KDF_SALT] = java.util.Base64.getEncoder().encodeToString(kdf.kdfSalt)
                }
                is Kdf.Vault -> {
                    m[P_KDF] = "vault"
                    m[P_VAULT_REF] = kdf.vaultRef
                }
            }
            return m
        }

        /**
         * Reads exactly one header from [input] (blocking). Throws [CipherIntegrityException] with
         * BAD_HEADER / UNSUPPORTED_VERSION for anything that is not a well-formed v1 header.
         */
        fun read(input: InputStream): Encoded {
            val raw = ByteArrayOutputStream(96)
            fun bytes(n: Int): ByteArray {
                val b = ByteArray(n)
                var off = 0
                while (off < n) {
                    val r = try {
                        input.read(b, off, n - off)
                    } catch (_: EOFException) {
                        -1
                    }
                    if (r < 0) throw bad("Stream ended inside the header")
                    off += r
                }
                raw.write(b)
                return b
            }
            fun u8(): Int = bytes(1)[0].toInt() and 0xFF
            fun u16(): Int = bytes(2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
            fun u32(): Long = bytes(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

            if (!bytes(MAGIC.size).contentEquals(MAGIC)) throw bad("Not a DFTB encrypted stream")
            val version = u8()
            if (version != VERSION) {
                throw CipherIntegrityException(CipherIntegrityException.Reason.UNSUPPORTED_VERSION, "Unsupported stream version $version")
            }
            val kdf = when (val id = u8()) {
                KDF_ARGON2ID -> {
                    val m = u32()
                    val t = u32()
                    val p = u8()
                    if (m > Int.MAX_VALUE || t > Int.MAX_VALUE || !Argon2Settings.isAcceptable(m.toInt(), t.toInt(), p)) {
                        throw bad("Argon2 parameters out of range")
                    }
                    val kdfSaltLen = u8()
                    if (kdfSaltLen !in MIN_SALT..MAX_SALT) throw bad("KDF salt length out of range")
                    Kdf.Argon2id(Argon2Settings(m.toInt(), t.toInt(), p), bytes(kdfSaltLen))
                }
                KDF_VAULT -> {
                    val len = u16()
                    if (len !in 1..MAX_REF_BYTES) throw bad("Vault ref length out of range")
                    val refBytes = bytes(len)
                    val ref = try {
                        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(refBytes)).toString()
                    } catch (_: java.nio.charset.CharacterCodingException) {
                        throw bad("Vault ref is not UTF-8")
                    }
                    Kdf.Vault(ref)
                }
                else -> throw CipherIntegrityException(CipherIntegrityException.Reason.UNSUPPORTED_VERSION, "Unknown KDF id $id")
            }
            val saltLen = u8()
            if (saltLen !in MIN_SALT..MAX_SALT) throw bad("Salt length out of range")
            val salt = bytes(saltLen)
            val noncePrefix = bytes(NONCE_PREFIX_BYTES)
            val chunk = u32()
            if (chunk !in MIN_CHUNK.toLong()..MAX_CHUNK.toLong()) throw bad("Chunk size out of range")
            val keyCheck = bytes(KEY_CHECK_BYTES)
            val header = StreamHeader(version, kdf, salt, noncePrefix, chunk.toInt(), keyCheck)
            return Encoded(header, raw.toByteArray())
        }

        private fun bad(msg: String) = CipherIntegrityException(CipherIntegrityException.Reason.BAD_HEADER, msg)
    }
}
