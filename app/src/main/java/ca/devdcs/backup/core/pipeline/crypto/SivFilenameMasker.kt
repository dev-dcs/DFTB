package ca.devdcs.backup.core.pipeline.crypto

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import ca.devdcs.backup.core.pipeline.FilenameMasker
import ca.devdcs.backup.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-secret inputs a masker key is derived from. They must be stored with the backup (manifest)
 * because masking is deterministic: restoring needs the very same key, i.e. the same salt and, for
 * passphrases, the same Argon2 cost.
 */
data class MaskerParams(val salt: ByteArray, val argon2: Argon2Settings = Argon2Settings()) {
    init {
        require(salt.size in StreamHeader.MIN_SALT..StreamHeader.MAX_SALT) { "Salt length out of range" }
    }

    fun toMap(): Map<String, String> = linkedMapOf(
        P_SCHEME to SivFilenameMasker.SCHEME,
        P_SALT to Base64.getEncoder().encodeToString(salt),
        StreamHeader.P_MEMORY_KIB to argon2.memoryKiB.toString(),
        StreamHeader.P_ITERATIONS to argon2.iterations.toString(),
        StreamHeader.P_PARALLELISM to argon2.parallelism.toString(),
    )

    override fun equals(other: Any?): Boolean = other is MaskerParams && argon2 == other.argon2 && salt.contentEquals(other.salt)
    override fun hashCode(): Int = salt.contentHashCode() * 31 + argon2.hashCode()

    companion object {
        const val P_SCHEME = "scheme"
        const val P_SALT = "salt"

        /** Parses [toMap] output; null when the map is not a valid `dftb-mask-v1` description. */
        fun fromMap(map: Map<String, String>): MaskerParams? {
            if (map[P_SCHEME] != SivFilenameMasker.SCHEME) return null
            val salt = try {
                Base64.getDecoder().decode(map[P_SALT] ?: return null)
            } catch (_: IllegalArgumentException) {
                return null
            }
            if (salt.size !in StreamHeader.MIN_SALT..StreamHeader.MAX_SALT) return null
            val m = map[StreamHeader.P_MEMORY_KIB]?.toIntOrNull() ?: return null
            val t = map[StreamHeader.P_ITERATIONS]?.toIntOrNull() ?: return null
            val p = map[StreamHeader.P_PARALLELISM]?.toIntOrNull() ?: return null
            if (!Argon2Settings.isAcceptable(m, t, p)) return null
            return MaskerParams(salt, Argon2Settings(m, t, p))
        }
    }
}

/**
 * Builds [SivFilenameMasker]s. A factory rather than a singleton masker because each backup set
 * has its own key + salt, and deriving a passphrase key costs one Argon2 run (done once here, then
 * every name is masked with cheap HMAC/AES operations).
 */
@Singleton
class FilenameMaskerFactory @Inject constructor(
    private val keys: VaultKeyStore,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Fresh random params for a new backup set; persist [MaskerParams.toMap] in the manifest. */
    fun newParams(argon2: Argon2Settings = Argon2Settings()): MaskerParams =
        MaskerParams(Secrets.randomBytes(StreamHeader.DEFAULT_SALT), argon2)

    /**
     * Derives the masking keys from [key] (Argon2id for passphrases, the vault key otherwise),
     * then HKDF with the masker's own info labels, so masker keys are independent of stream keys
     * even though they come from the same secret. Close the masker when done to wipe its keys.
     */
    suspend fun create(key: CipherKeySpec, params: MaskerParams): SivFilenameMasker = withContext(io) {
        val kdf = when (key) {
            is CipherKeySpec.Passphrase -> StreamHeader.Kdf.Argon2id(params.argon2, params.salt)
            is CipherKeySpec.VaultKey -> StreamHeader.Kdf.Vault(key.vaultRef)
        }
        val master = AesGcmStreamCipher.masterKey(keys, key, kdf)
        try {
            SivFilenameMasker.fromMasterKey(master, params.salt)
        } finally {
            master.fill(0)
        }
    }
}

/**
 * Deterministic authenticated name masking, an SIV construction (RFC 5297 style):
 * `tag = HMAC-SHA256(macKey, utf8(name))[0..16]`, `ct = AES-256-CTR(encKey, iv = tag, utf8(name))`,
 * `masked = base32lower(tag || ct)`.
 *
 * Deterministic so that incremental backups/sync find the same remote object for the same file;
 * the tag doubles as an integrity check, so [unmask] returns null for anything not produced by
 * this key. Leaks only name equality and approximate length.
 *
 * Output alphabet is `a-z2-7`. A name of n UTF-8 bytes masks to `ceil((16 + n) * 8 / 5)` chars;
 * names are limited to [MAX_NAME_BYTES] (143) bytes so the result fits the common 255-byte
 * filename limit ([MAX_MASKED_LENGTH]).
 */
class SivFilenameMasker private constructor(macKey: ByteArray, encKey: ByteArray) : FilenameMasker, AutoCloseable {

    private val macKeySpec = SecretKeySpec(macKey, "HmacSHA256").also { macKey.fill(0) }
    private val encKeySpec = SecretKeySpec(encKey, "AES").also { encKey.fill(0) }
    @Volatile private var closed = false

    /** True when [name] can be masked (non-empty and at most [MAX_NAME_BYTES] UTF-8 bytes). */
    fun canMask(name: String): Boolean {
        if (name.isEmpty()) return false
        val bytes = strictUtf8(name) ?: return false
        return bytes.size <= MAX_NAME_BYTES
    }

    /**
     * Masks one name (a single path segment). Throws IllegalArgumentException for an empty name,
     * invalid UTF-16, or more than [MAX_NAME_BYTES] bytes; check [canMask] first for user input.
     */
    override fun mask(name: String): String {
        check(!closed) { "Masker closed" }
        require(name.isNotEmpty()) { "Cannot mask an empty name" }
        val plain = requireNotNull(strictUtf8(name)) { "Name is not valid Unicode" }
        require(plain.size <= MAX_NAME_BYTES) { "Name exceeds $MAX_NAME_BYTES UTF-8 bytes" }
        val tag = tag(plain)
        val ct = ctr(tag, plain)
        val out = ByteArray(TAG_BYTES + ct.size)
        System.arraycopy(tag, 0, out, 0, TAG_BYTES)
        System.arraycopy(ct, 0, out, TAG_BYTES, ct.size)
        return Base32.encode(out)
    }

    /** Original name, or null when [masked] is not a valid masked name under this key. */
    override fun unmask(masked: String): String? {
        check(!closed) { "Masker closed" }
        if (masked.length > MAX_MASKED_LENGTH) return null
        val raw = Base32.decode(masked) ?: return null
        if (raw.size <= TAG_BYTES) return null
        val tag = raw.copyOfRange(0, TAG_BYTES)
        val plain = ctr(tag, raw.copyOfRange(TAG_BYTES, raw.size))
        try {
            if (!MessageDigest.isEqual(tag, tag(plain))) return null
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plain)).toString()
            } catch (_: CharacterCodingException) {
                null
            }
        } finally {
            plain.fill(0)
        }
    }

    /** Masks every segment of a '/'-separated relative path; empty segments are rejected. */
    fun maskPath(path: String): String {
        val segments = path.split('/')
        require(segments.none { it.isEmpty() }) { "Path has empty segments" }
        return segments.joinToString("/") { mask(it) }
    }

    /** Inverse of [maskPath]; null if any segment is not a valid masked name. */
    fun unmaskPath(masked: String): String? {
        val segments = masked.split('/')
        val out = ArrayList<String>(segments.size)
        for (s in segments) out += unmask(s) ?: return null
        return out.joinToString("/")
    }

    override fun close() {
        closed = true
        Secrets.destroy(macKeySpec)
        Secrets.destroy(encKeySpec)
    }

    private fun tag(plain: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(macKeySpec)
        return mac.doFinal(plain).copyOf(TAG_BYTES)
    }

    private fun ctr(iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encKeySpec, IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun strictUtf8(name: String): ByteArray? = try {
        val bb = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(name))
        ByteArray(bb.remaining()).also { bb.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }

    companion object {
        const val SCHEME = "dftb-mask-v1"
        const val TAG_BYTES = 16
        /** Longest maskable name in UTF-8 bytes: ceil((16 + 143) * 8 / 5) = 255 chars. */
        const val MAX_NAME_BYTES = 143
        const val MAX_MASKED_LENGTH = 255
        private val INFO_MAC = "dftb-mask-v1/mac".toByteArray(Charsets.US_ASCII)
        private val INFO_ENC = "dftb-mask-v1/enc".toByteArray(Charsets.US_ASCII)

        /** Builds a masker from a 256-bit master key; [master] is not modified (caller wipes it). */
        fun fromMasterKey(master: ByteArray, salt: ByteArray): SivFilenameMasker =
            SivFilenameMasker(Hkdf.sha256(master, salt, INFO_MAC, 32), Hkdf.sha256(master, salt, INFO_ENC, 32))
    }
}
