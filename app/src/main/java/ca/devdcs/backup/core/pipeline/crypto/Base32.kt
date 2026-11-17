package ca.devdcs.backup.core.pipeline.crypto

/**
 * RFC 4648 base32, lowercase, no padding. Chosen for masked file names because its alphabet
 * (`a-z2-7`) is safe on every filesystem DFTB writes to, including case-insensitive FAT/exFAT/SMB
 * shares where base64 would collide. Decoding is strict (canonical form only) so each byte string
 * has exactly one valid name.
 */
internal object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private val DECODE = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encodedLength(byteCount: Int): Int = (byteCount * 8 + 4) / 5

    fun encode(data: ByteArray): String {
        val sb = StringBuilder(encodedLength(data.size))
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer ushr (bits - 5)) and 0x1F])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    /** Null for any character outside the alphabet, an impossible length, or non-zero pad bits. */
    fun decode(text: String): ByteArray? {
        val rem = text.length % 8
        if (rem == 1 || rem == 3 || rem == 6) return null
        val out = ByteArray(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        var o = 0
        for (c in text) {
            val v = if (c.code < 128) DECODE[c.code] else -1
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out[o++] = (buffer ushr (bits - 8)).toByte()
                bits -= 8
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        if (buffer != 0) return null
        return out
    }
}
