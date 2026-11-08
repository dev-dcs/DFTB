package ca.devdcs.backup.core.pipeline.checksum

import ca.devdcs.backup.domain.model.ChecksumAlgorithm
import java.io.InputStream
import java.io.OutputStream

/**
 * Reads and writes checksum sidecar files (`backup.tar.zst.sha256` next to the artifact) in the
 * GNU coreutils text format, so a user can verify a backup on any PC with `sha256sum -c` /
 * `md5sum -c` / `b3sum -c` without DFTB. Also understands TWRP's `.sha2` naming and the BSD
 * `SHA256 (name) = hex` tag format, so existing TWRP/BSD sidecars can be verified on restore.
 */
object ChecksumSidecar {

    /** One `<hex> <name>` line. [algorithm] is known only for BSD tag lines (`ALG (name) = hex`). */
    data class Entry(
        val hex: String,
        val fileName: String,
        /** `*name` (binary mode) on GNU lines; irrelevant on POSIX but preserved for round-trips. */
        val binaryMode: Boolean = false,
        val algorithm: ChecksumAlgorithm? = null,
    )

    /** Parsed sidecar. Malformed lines are reported (1-based) instead of failing the whole file. */
    data class ParseResult(val entries: List<Entry>, val malformedLines: List<Int>)

    /** Naming convention for the sidecar file. */
    enum class Style {
        /** `<artifact>.md5`, `.sha1`, `.sha256`, `.sha512`, `.blake3`. */
        STANDARD,
        /** TWRP: SHA-256 sidecars are `<artifact>.sha2`; other algorithms keep the standard extension. */
        TWRP,
    }

    const val TWRP_SHA256_EXTENSION = ".sha2"

    /** Sidecar file name for [artifactName] (a plain name, not a path). */
    fun sidecarName(artifactName: String, algorithm: ChecksumAlgorithm, style: Style = Style.STANDARD): String {
        val ext = if (style == Style.TWRP && algorithm == ChecksumAlgorithm.SHA256) TWRP_SHA256_EXTENSION else algorithm.fileExtension
        return artifactName + ext
    }

    /**
     * Infers the algorithm from a sidecar file name (`x.sha256`, `x.sha256sum`, `x.sha2`, `x.md5`,
     * `SHA256SUMS`, ...). Null when the name carries no recognisable algorithm.
     */
    fun algorithmForSidecarName(fileName: String): ChecksumAlgorithm? {
        val lower = fileName.lowercase()
        val ext = lower.substringAfterLast('.', missingDelimiterValue = lower).removeSuffix("sum").removeSuffix("sums")
        return when (ext) {
            "md5" -> ChecksumAlgorithm.MD5
            "sha1" -> ChecksumAlgorithm.SHA1
            "sha2", "sha256" -> ChecksumAlgorithm.SHA256
            "sha512" -> ChecksumAlgorithm.SHA512
            "blake3", "b3" -> ChecksumAlgorithm.BLAKE3
            else -> null
        }
    }

    /**
     * Best guess from digest length when the file name gives no hint. 64 hex chars is ambiguous
     * (SHA-256 vs BLAKE3); SHA-256 is returned as it is by far the more common.
     */
    fun algorithmForHexLength(hexLength: Int): ChecksumAlgorithm? = when (hexLength) {
        32 -> ChecksumAlgorithm.MD5
        40 -> ChecksumAlgorithm.SHA1
        64 -> ChecksumAlgorithm.SHA256
        128 -> ChecksumAlgorithm.SHA512
        else -> null
    }

    /**
     * One line, newline-terminated, exactly as `sha256sum` prints it. Names containing a backslash,
     * LF or CR use the coreutils escape form (leading `\`, then `\\`, `\n`, `\r`).
     */
    fun formatLine(hex: String, fileName: String, binaryMode: Boolean = false): String {
        require(isHex(hex)) { "Not a hex digest" }
        val needsEscape = fileName.any { it == '\\' || it == '\n' || it == '\r' }
        val name = if (needsEscape) escape(fileName) else fileName
        val sb = StringBuilder(hex.length + name.length + 4)
        if (needsEscape) sb.append('\\')
        sb.append(hex.lowercase()).append(' ').append(if (binaryMode) '*' else ' ').append(name).append('\n')
        return sb.toString()
    }

    fun format(entries: List<Entry>): String = buildString {
        for (e in entries) append(formatLine(e.hex, e.fileName, e.binaryMode))
    }

    fun write(out: OutputStream, entries: List<Entry>) {
        out.write(format(entries).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Reads (and does not close) [input]; sidecars are tiny, the whole stream is read. */
    fun read(input: InputStream): ParseResult = parse(input.readBytes().toString(Charsets.UTF_8))

    fun parse(text: String): ParseResult {
        val entries = mutableListOf<Entry>()
        val malformed = mutableListOf<Int>()
        // Split on LF only: CR may legitimately be part of an escaped name; strip a CRLF's CR.
        text.split('\n').forEachIndexed { index, rawLine ->
            val line = rawLine.removeSuffix("\r")
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed
            val entry = parseGnu(line) ?: parseBsd(line)
            if (entry != null) entries += entry else malformed += index + 1
        }
        return ParseResult(entries, malformed)
    }

    /** Case-insensitive comparison that also tolerates surrounding whitespace in stored values. */
    fun matches(expectedHex: String, actualHex: String): Boolean =
        expectedHex.trim().equals(actualHex.trim(), ignoreCase = true)

    private fun parseGnu(line: String): Entry? {
        var s = line
        val escaped = s.startsWith("\\")
        if (escaped) s = s.substring(1)
        val sep = s.indexOf(' ')
        if (sep <= 0 || sep + 2 > s.length) return null
        val hex = s.substring(0, sep)
        if (!isHex(hex) || algorithmForHexLength(hex.length) == null) return null
        val mode = s[sep + 1]
        if (mode != ' ' && mode != '*') return null
        val rawName = s.substring(sep + 2)
        if (rawName.isEmpty()) return null
        val name = if (escaped) unescape(rawName) ?: return null else rawName
        return Entry(hex.lowercase(), name, binaryMode = mode == '*')
    }

    private val BSD = Regex("""^\\?(MD5|SHA1|SHA256|SHA512|BLAKE3) \((.+)\) = ([0-9a-fA-F]+)$""")

    private fun parseBsd(line: String): Entry? {
        val m = BSD.matchEntire(line) ?: return null
        val algorithm = when (m.groupValues[1]) {
            "MD5" -> ChecksumAlgorithm.MD5
            "SHA1" -> ChecksumAlgorithm.SHA1
            "SHA256" -> ChecksumAlgorithm.SHA256
            "SHA512" -> ChecksumAlgorithm.SHA512
            else -> ChecksumAlgorithm.BLAKE3
        }
        val hex = m.groupValues[3]
        if (hex.length != algorithm.digestLengthBytes * 2) return null
        val name = if (line.startsWith("\\")) unescape(m.groupValues[2]) ?: return null else m.groupValues[2]
        return Entry(hex.lowercase(), name, algorithm = algorithm)
    }

    private fun isHex(s: String): Boolean =
        s.isNotEmpty() && s.length % 2 == 0 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun escape(name: String): String = buildString(name.length + 4) {
        for (c in name) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(c)
        }
    }

    private fun unescape(name: String): String? {
        val sb = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c != '\\') { sb.append(c); i++; continue }
            if (i + 1 >= name.length) return null
            when (name[i + 1]) {
                '\\' -> sb.append('\\')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                else -> return null
            }
            i += 2
        }
        return sb.toString()
    }
}
