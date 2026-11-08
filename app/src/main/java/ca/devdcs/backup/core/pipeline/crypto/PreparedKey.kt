package ca.devdcs.backup.core.pipeline.crypto

import ca.devdcs.backup.core.pipeline.CipherKeySpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A job-level master key from [AesGcmStreamCipher.prepare]: the Argon2id output (or vault key)
 * plus the KDF parameters/salt every stream header records. Exists so a FILE_TREE backup of 10k
 * files runs Argon2 once instead of once per file. [close] wipes the master; thread-safe.
 */
class PreparedKey internal constructor(
    master: ByteArray,
    /** KDF id, parameters and Argon2 salt written to every stream header (no secrets). */
    val kdf: StreamHeader.Kdf,
) : AutoCloseable {

    private val lock = Any()
    private var master: ByteArray? = master

    internal fun <T> withMaster(block: (ByteArray) -> T): T = synchronized(lock) {
        block(master ?: throw IllegalStateException("PreparedKey closed"))
    }

    override fun close() {
        synchronized(lock) {
            master?.fill(0)
            master = null
        }
    }
}

/**
 * Restore-side counterpart of [PreparedKey]: remembers the last [capacity] masters derived for
 * [key], keyed by (Argon2 salt + params) or vault ref, so decrypting many streams of one job runs
 * Argon2 once. Entries are evicted least-recently-used and wiped; [close] wipes all. Thread-safe;
 * concurrent misses for the same entry derive once.
 */
class DecryptKeyCache(
    val key: CipherKeySpec,
    private val capacity: Int = DEFAULT_CAPACITY,
) : AutoCloseable {

    init {
        require(capacity >= 1) { "capacity must be >= 1" }
    }

    private val mutex = Mutex()
    private val entries = object : LinkedHashMap<StreamHeader.Kdf, ByteArray>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StreamHeader.Kdf, ByteArray>): Boolean {
            if (size > this@DecryptKeyCache.capacity) {
                eldest.value.fill(0)
                return true
            }
            return false
        }
    }
    private var closed = false

    /** Number of cached masters (for tests/diagnostics). */
    val size: Int get() = synchronized(entries) { entries.size }

    internal suspend fun <T> withMaster(kdf: StreamHeader.Kdf, derive: suspend () -> ByteArray, block: (ByteArray) -> T): T =
        mutex.withLock {
            check(synchronized(entries) { !closed }) { "DecryptKeyCache closed" }
            val master = synchronized(entries) { entries[kdf] } ?: derive().also { m ->
                synchronized(entries) {
                    if (closed) {
                        m.fill(0)
                        throw IllegalStateException("DecryptKeyCache closed")
                    }
                    entries[kdf] = m
                }
            }
            block(master)
        }

    override fun close() {
        synchronized(entries) {
            closed = true
            for (v in entries.values) v.fill(0)
            entries.clear()
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 4
    }
}
