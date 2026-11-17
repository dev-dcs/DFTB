package ca.devdcs.backup.data.security

import ca.devdcs.backup.domain.security.VaultException

/**
 * Maps a free-form ref to a filename and back. Lower-case hex of the UTF-8 bytes is used rather
 * than base64url because it stays bijective even on case-insensitive filesystems (developer
 * machines running the JVM tests) and contains nothing a shell, SAF or a ZIP tool could mangle.
 *
 * A ref may be at most [MAX_REF_BYTES] UTF-8 bytes so the resulting name fits the 255-byte limit
 * common to ext4 and f2fs; `newRef()` output (`prefix-UUID`) is far below that.
 */
object VaultRefCodec {

    const val FILE_EXTENSION: String = ".dftbsec"
    const val TEMP_SUFFIX: String = ".tmp"

    /**
     * 255-byte filename limit minus the extension and the temp suffix (a temp write appends
     * [TEMP_SUFFIX] to the final name), halved for hex, so both the final and the temp name fit.
     */
    const val MAX_REF_BYTES: Int = (255 - FILE_EXTENSION.length - TEMP_SUFFIX.length) / 2

    private const val HEX = "0123456789abcdef"

    fun toFileName(ref: String): String {
        val bytes = ref.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) throw VaultException("Vault ref must not be empty", reason = VaultException.Reason.INVALID_REF)
        if (bytes.size > MAX_REF_BYTES) {
            throw VaultException("Vault ref is ${bytes.size} bytes, limit is $MAX_REF_BYTES", reason = VaultException.Reason.INVALID_REF)
        }
        // Reject refs that do not survive a UTF-8 round trip (e.g. unpaired surrogates, which
        // encode to '?'); otherwise the ref->filename mapping would not be bijective and distinct
        // refs could collide on the same file.
        if (String(bytes, Charsets.UTF_8) != ref) {
            throw VaultException("Vault ref contains characters that cannot be encoded as UTF-8", reason = VaultException.Reason.INVALID_REF)
        }
        val out = StringBuilder(bytes.size * 2 + FILE_EXTENSION.length)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.append(FILE_EXTENSION).toString()
    }

    /** Inverse of [toFileName]; null for names that were not produced by it (temp files, stray files). */
    fun fromFileName(fileName: String): String? {
        if (!fileName.endsWith(FILE_EXTENSION)) return null
        val hex = fileName.substring(0, fileName.length - FILE_EXTENSION.length)
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (i in bytes.indices) {
            val hi = HEX.indexOf(hex[i * 2])
            val lo = HEX.indexOf(hex[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            bytes[i] = ((hi shl 4) or lo).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }
}
