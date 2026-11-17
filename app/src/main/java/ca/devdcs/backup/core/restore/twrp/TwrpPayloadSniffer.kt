package ca.devdcs.backup.core.restore.twrp

/**
 * Pure format detection for `.win` files from their first bytes. Mirrors TWRP's own
 * `TWFunc::Get_File_Type` (0x1f 0x8b = compressed, `OA` = OpenAES-encrypted) but checks the tar
 * magic first so a tar whose first member name starts with "OA" is not mistaken for ciphertext.
 */
object TwrpPayloadSniffer {

    /** Bytes [sniff] wants: one tar header block. */
    const val HEADER_BYTES = 512

    private const val TAR_MAGIC_OFFSET = 257
    private const val TAR_CHECKSUM_OFFSET = 148
    private const val TAR_CHECKSUM_LENGTH = 8

    fun sniff(header: ByteArray, length: Int, kind: TwrpPayloadKind): TwrpPayloadFormat {
        val n = length.coerceIn(0, header.size)
        if (n == 0) return TwrpPayloadFormat.EMPTY
        if (kind == TwrpPayloadKind.RAW_IMAGE) return TwrpPayloadFormat.RAW
        if (isTar(header, n)) return TwrpPayloadFormat.TAR
        if (n >= 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()) return TwrpPayloadFormat.GZIP
        if (n >= 2 && header[0] == 'O'.code.toByte() && header[1] == 'A'.code.toByte()) return TwrpPayloadFormat.ENCRYPTED
        return TwrpPayloadFormat.UNKNOWN
    }

    /** `ustar` magic, or (old v7 tar without magic) a valid header checksum. */
    fun isTar(header: ByteArray, length: Int): Boolean {
        if (length < HEADER_BYTES) return false
        val magic = String(header, TAR_MAGIC_OFFSET, 5, Charsets.US_ASCII)
        if (magic == "ustar") return true
        return checksumValid(header)
    }

    private fun checksumValid(header: ByteArray): Boolean {
        val field = String(header, TAR_CHECKSUM_OFFSET, TAR_CHECKSUM_LENGTH, Charsets.US_ASCII).trim { it == ' ' || it == '\u0000' }
        if (field.isEmpty() || field.any { it !in '0'..'7' }) return false
        val recorded = field.toLong(8)
        var sum = 0L
        for (i in 0 until HEADER_BYTES) {
            val inChecksum = i >= TAR_CHECKSUM_OFFSET && i < TAR_CHECKSUM_OFFSET + TAR_CHECKSUM_LENGTH
            sum += if (inChecksum) ' '.code.toLong() else (header[i].toInt() and 0xff).toLong()
        }
        // An all-zero block sums to 256 (8 spaces); never a valid first header.
        return sum == recorded && sum != 8L * ' '.code
    }
}

/**
 * Pure path rules for extracting TWRP tar members into a folder. Exists so a hostile or damaged
 * archive can never write outside the chosen folder and so single vs split naming is handled once.
 */
object TwrpArchivePaths {

    /**
     * Relative output path of member [entryName], or null when it must be skipped (absolute after
     * stripping, `..`, empty). Split archives store names relative to `/` (`data/app/x`) while a
     * single archive stores them relative to the mount point (`app/x`); [stripRootSegment] (the
     * mount name, e.g. `data`, for split sets) removes the leading segment so both land identically.
     */
    fun outputPath(entryName: String, stripRootSegment: String?): String? {
        var segments = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (stripRootSegment != null && segments.firstOrNull() == stripRootSegment) segments = segments.drop(1)
        if (segments.isEmpty() || segments.any { it == ".." || it.any { c -> c.code < 0x20 } }) return null
        return segments.joinToString("/")
    }
}
