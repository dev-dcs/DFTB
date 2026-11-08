package ca.devdcs.backup.core.pipeline.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom

/**
 * Argon2id cost parameters. Persisted in every stream header / masker params, so changing the
 * defaults never breaks old backups. Defaults follow RFC 9106's memory-constrained profile scaled
 * to a phone: 64 MiB, 3 passes, 1 lane (BouncyCastle computes lanes sequentially, so extra lanes
 * add no speed on Android). Low-RAM devices may lower [memoryKiB]; decryption accepts anything
 * within the [isAcceptable] bounds.
 */
data class Argon2Settings(
    val memoryKiB: Int = DEFAULT_MEMORY_KIB,
    val iterations: Int = DEFAULT_ITERATIONS,
    val parallelism: Int = DEFAULT_PARALLELISM,
) {
    init {
        require(isAcceptable()) { "Argon2 parameters out of range: m=$memoryKiB t=$iterations p=$parallelism" }
    }

    /** Bounds also used to reject hostile headers that would make decryption exhaust RAM or CPU. */
    fun isAcceptable(): Boolean = isAcceptable(memoryKiB, iterations, parallelism)

    companion object {
        const val DEFAULT_MEMORY_KIB = 64 * 1024
        const val DEFAULT_ITERATIONS = 3
        const val DEFAULT_PARALLELISM = 1
        /** 256 MiB hard cap: above typical Android per-app heaps, so hostile headers are refused. */
        const val MAX_MEMORY_KIB = 256 * 1024
        const val MAX_ITERATIONS = 32
        const val MAX_PARALLELISM = 8

        fun isAcceptable(memoryKiB: Int, iterations: Int, parallelism: Int): Boolean =
            parallelism in 1..MAX_PARALLELISM && iterations in 1..MAX_ITERATIONS &&
                memoryKiB in (8 * parallelism)..MAX_MEMORY_KIB
    }
}

/** Thin, testable wrapper over BouncyCastle's Argon2id (RFC 9106, version 0x13). */
object Argon2Kdf {

    /**
     * Derives [outLength] bytes. [secret]/[associatedData] are the optional RFC 9106 K and X
     * inputs (unused by DFTB streams, exposed for the RFC known-answer test).
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        settings: Argon2Settings,
        outLength: Int = 32,
        secret: ByteArray? = null,
        associatedData: ByteArray? = null,
    ): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(settings.memoryKiB)
            .withIterations(settings.iterations)
            .withParallelism(settings.parallelism)
            .withSalt(salt)
        if (secret != null) builder.withSecret(secret)
        if (associatedData != null) builder.withAdditional(associatedData)
        val params = builder.build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val out = ByteArray(outLength)
        try {
            generator.generateBytes(password, out)
        } finally {
            params.clear()
            builder.clear()
        }
        return out
    }

    /** Derives a 256-bit key from a passphrase; the UTF-8 copy of the passphrase is wiped. */
    fun deriveFromPassphrase(chars: CharArray, salt: ByteArray, settings: Argon2Settings): ByteArray {
        require(chars.isNotEmpty()) { "Empty passphrase" }
        val pw = Secrets.utf8(chars)
        try {
            return derive(pw, salt, settings)
        } finally {
            pw.fill(0)
        }
    }
}

/** HKDF-SHA256 (RFC 5869); used to split one master key into independent purpose-bound subkeys. */
internal object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }
}

/** Small helpers for handling key material without leaving copies around. */
internal object Secrets {
    val random: SecureRandom by lazy { SecureRandom() }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** Strict UTF-8 encode of [chars] into a fresh array; the encoder's buffer is wiped. */
    fun utf8(chars: CharArray): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val bb = encoder.encode(CharBuffer.wrap(chars))
        val out = ByteArray(bb.remaining())
        bb.get(out)
        if (bb.hasArray()) bb.array().fill(0)
        return out
    }

    /** Best-effort wipe of a JCA key object (Android/JDK SecretKeySpec may refuse destroy()). */
    fun destroy(key: javax.security.auth.Destroyable?) {
        if (key == null) return
        try {
            key.destroy()
        } catch (_: Exception) {
            // DestroyFailedException: the provider keeps its own copy; nothing more can be done.
        }
    }
}
