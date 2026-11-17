package ca.devdcs.backup.core.storage.rclone

/**
 * One `[name]` block of an rclone.conf. [options] keeps file order and includes `type`.
 */
data class RcloneSection(
    val name: String,
    val options: Map<String, String>,
) {
    /** The rclone backend (`s3`, `drive`, ...), or null when the section lacks `type`. */
    val type: String? get() = options["type"]
}

/**
 * A configured remote as shown to the user: secret-looking option values are masked so a remote
 * list or detail screen never reveals tokens or passwords.
 */
data class RcloneRemote(
    val name: String,
    val type: String,
    /** Option values with secrets replaced by [RcloneIni.MASK]; `type` excluded. */
    val maskedOptions: Map<String, String>,
)

/** A problem found while validating rclone.conf text. [line] is 1-based, or null when global. */
data class RcloneIniIssue(
    val severity: Severity,
    val code: Code,
    val line: Int?,
    val message: String,
) {
    enum class Severity { ERROR, WARNING }

    enum class Code {
        MALFORMED_LINE,
        KEY_OUTSIDE_SECTION,
        INVALID_SECTION_NAME,
        DUPLICATE_SECTION,
        DUPLICATE_KEY,
        EMPTY_KEY,
        MISSING_TYPE,
        UNKNOWN_TYPE,
    }
}

/** Result of [RcloneIni.parse]: sections in file order plus every issue found. */
data class RcloneIniDocument(
    val sections: List<RcloneSection>,
    val issues: List<RcloneIniIssue>,
) {
    val errors: List<RcloneIniIssue> get() = issues.filter { it.severity == RcloneIniIssue.Severity.ERROR }
    val warnings: List<RcloneIniIssue> get() = issues.filter { it.severity == RcloneIniIssue.Severity.WARNING }
    val isValid: Boolean get() = errors.isEmpty()

    fun section(name: String): RcloneSection? = sections.firstOrNull { it.name == name }
}

/**
 * Pure parser, validator and editor for rclone.conf INI text.
 *
 * Exists because the config is user-editable (raw editor, pasted sections, guided setup) and a
 * broken file makes every rclone call fail with an opaque error; validating before saving lets the
 * UI point at the offending line instead. It follows rclone's own (goconfig) dialect: `#`/`;`
 * comment lines, `[section]` headers, `key = value` split on the first `=`, values trimmed.
 */
object RcloneIni {

    /** Display placeholder for masked secret values. */
    const val MASK: String = "••••••"

    /**
     * Backend types this rclone generation knows. A type outside the list is only a warning: newer
     * rclone builds add backends, and the user may run one.
     */
    val KNOWN_TYPES: Set<String> = setOf(
        "alias", "amazonclouddrive", "azureblob", "azurefiles", "b2", "box", "cache", "chunker",
        "cloudinary", "combine", "compress", "crypt", "doi", "drive", "dropbox", "fichier", "filefabric",
        "filescom", "ftp", "gofile", "googlecloudstorage", "google photos", "gphotos", "hasher", "hdfs",
        "hidrive", "http", "iclouddrive", "imagekit", "internetarchive", "jottacloud", "koofr", "linkbox",
        "local", "mailru", "mega", "memory", "netstorage", "onedrive", "opendrive", "oracleobjectstorage",
        "pcloud", "pikpak", "pixeldrain", "premiumizeme", "protondrive", "putio", "qingstor", "quatrix",
        "s3", "seafile", "sftp", "sharefile", "sia", "smb", "storj", "sugarsync", "swift", "tardigrade",
        "ulozto", "union", "uptobox", "webdav", "yandex", "zoho",
    )

    /** Option keys whose values are always secret, regardless of naming heuristics. */
    private val SECRET_KEYS: Set<String> = setOf(
        "key", "pass", "password", "password2", "token", "client_secret", "secret_access_key",
        "session_token", "key_pem", "key_file_pass", "sas_url", "service_account_credentials",
        "bearer_token", "app_key", "api_key", "access_token", "refresh_token", "account_key",
        "connection_string", "credentials", "keyfile_pass", "shared_key",
    )

    /** rclone remote-name rule: letters, digits, `_ - . + @` and space; not starting with `-`/space, not ending with space. */
    private val REMOTE_NAME = Regex("^[\\p{L}\\p{N}_.+@][\\p{L}\\p{N}_.+@ -]*$")

    fun isValidRemoteName(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_REMOTE_NAME && REMOTE_NAME.matches(name) && !name.endsWith(' ')

    /** `*_key`-style options that are identifiers or paths, not secrets. */
    private val NON_SECRET_KEY_OPTIONS: Set<String> = setOf(
        "access_key_id", "kms_key_id", "sse_kms_key_id", "sse_customer_key_md5", "key_file", "pubkey_file",
        "key_use_agent", "known_hosts_file", "service_account_file",
    )

    fun isSecretKey(key: String): Boolean {
        val k = key.lowercase()
        if (k in NON_SECRET_KEY_OPTIONS) return false
        return k in SECRET_KEYS || "pass" in k || "secret" in k || "token" in k ||
            k.endsWith("_key") || k.endsWith("_keys") || k.endsWith("key_base64") || k.endsWith("_credentials")
    }

    /** Parse [text] and collect every structural and semantic issue. Never throws. */
    fun parse(text: String): RcloneIniDocument {
        val issues = mutableListOf<RcloneIniIssue>()
        val sections = mutableListOf<RcloneSection>()
        val seenSections = HashSet<String>()
        var currentName: String? = null
        var currentLine = 0
        var current: LinkedHashMap<String, String>? = null
        var currentIsDuplicate = false

        fun flush() {
            val name = currentName ?: return
            val opts = current ?: return
            if (!currentIsDuplicate) {
                sections += RcloneSection(name, opts.toMap())
                val type = opts["type"]
                if (type.isNullOrBlank()) {
                    issues += RcloneIniIssue(RcloneIniIssue.Severity.ERROR, RcloneIniIssue.Code.MISSING_TYPE, currentLine, "Remote [$name] has no 'type' option")
                } else if (type !in KNOWN_TYPES) {
                    issues += RcloneIniIssue(RcloneIniIssue.Severity.WARNING, RcloneIniIssue.Code.UNKNOWN_TYPE, currentLine, "Remote [$name] uses unknown backend type '$type'")
                }
            }
        }

        text.lineSequence().forEachIndexed { index, rawLine ->
            val lineNo = index + 1
            val line = rawLine.trim().removePrefix("﻿")
            when {
                line.isEmpty() || line.startsWith("#") || line.startsWith(";") -> Unit
                line.startsWith("[") -> {
                    flush()
                    if (!line.endsWith("]")) {
                        issues += RcloneIniIssue(RcloneIniIssue.Severity.ERROR, RcloneIniIssue.Code.MALFORMED_LINE, lineNo, "Section header is not closed with ']'")
                        currentName = null
                        current = null
                        return@forEachIndexed
                    }
                    val name = line.substring(1, line.length - 1)
                    if (!isValidRemoteName(name)) {
                        issues += RcloneIniIssue(RcloneIniIssue.Severity.ERROR, RcloneIniIssue.Code.INVALID_SECTION_NAME, lineNo, "Invalid remote name '[$name]'")
                    }
                    currentIsDuplicate = !seenSections.add(name)
                    if (currentIsDuplicate) {
                        issues += RcloneIniIssue(RcloneIniIssue.Severity.ERROR, RcloneIniIssue.Code.DUPLICATE_SECTION, lineNo, "Remote [$name] is defined more than once")
                    }
                    currentName = name
                    currentLine = lineNo
                    current = LinkedHashMap()
                }
                else -> {
                    val eq = line.indexOf('=')
                    if (eq < 0) {
                        issues += RcloneIniIssue(RcloneIniIssue.Severity.ERROR, RcloneIniIssue.Code.MALFORMED_LINE, lineNo, "Expected 'key = value'")
                        return@forEachIndexed
                    }
                    val key = line.substring(0, eq).trim()
                    val value = line.substring(eq + 1).trim()
                    val opts = current
                    when {
                        key.isEmpty() -> issues += RcloneIniIssue(RcloneIniIssue.Severity.ERROR, RcloneIniIssue.Code.EMPTY_KEY, lineNo, "Option has an empty key")
                        opts == null -> issues += RcloneIniIssue(RcloneIniIssue.Severity.ERROR, RcloneIniIssue.Code.KEY_OUTSIDE_SECTION, lineNo, "Option '$key' appears before any [remote] header")
                        opts.containsKey(key) -> {
                            issues += RcloneIniIssue(RcloneIniIssue.Severity.WARNING, RcloneIniIssue.Code.DUPLICATE_KEY, lineNo, "Option '$key' repeated in [$currentName]; the last value wins")
                            opts[key] = value
                        }
                        else -> opts[key] = value
                    }
                }
            }
        }
        flush()
        return RcloneIniDocument(sections, issues)
    }

    /** Render one section as INI text (header, `key = value` lines, trailing newline). */
    fun render(section: RcloneSection): String = buildString {
        append('[').append(section.name).append("]\n")
        // `type` first, as rclone writes it.
        section.options["type"]?.let { append("type = ").append(it).append('\n') }
        section.options.forEach { (k, v) -> if (k != "type") append(k).append(" = ").append(v).append('\n') }
    }

    /** Render a whole document; sections separated by a blank line, as rclone writes them. */
    fun render(sections: List<RcloneSection>): String = sections.joinToString("\n") { render(it) }

    /**
     * Replace the section named like [section] in [text], or append it. Comments and other sections
     * are preserved byte-for-byte; only the replaced block (header up to the next header) changes.
     */
    fun upsertSection(text: String, section: RcloneSection): String {
        val lines = text.lines()
        val range = sectionLineRange(lines, section.name)
        val rendered = render(section).trimEnd('\n').lines()
        return if (range == null) {
            val base = text.trimEnd('\n', '\r')
            if (base.isEmpty()) render(section) else base + "\n\n" + render(section)
        } else {
            val out = lines.subList(0, range.first) + rendered + trailingBlank(lines, range) + lines.subList(range.last + 1, lines.size)
            out.joinToString("\n").trimEnd('\n') + "\n"
        }
    }

    /** Remove the section [name] from [text]; returns [text] unchanged when absent. */
    fun removeSection(text: String, name: String): String {
        val lines = text.lines()
        val range = sectionLineRange(lines, name) ?: return text
        val out = lines.subList(0, range.first) + lines.subList(range.last + 1, lines.size)
        val joined = out.joinToString("\n").trim('\n')
        return if (joined.isEmpty()) "" else joined + "\n"
    }

    /** Display form of a section: secrets masked, `type` lifted out. Null when the section lacks a type. */
    fun toRemote(section: RcloneSection): RcloneRemote? {
        val type = section.type?.takeIf { it.isNotBlank() } ?: return null
        val masked = LinkedHashMap<String, String>()
        section.options.forEach { (k, v) ->
            if (k != "type") masked[k] = if (isSecretKey(k) && v.isNotEmpty()) MASK else v
        }
        return RcloneRemote(section.name, type, masked)
    }

    /** Lines [first, last] of section [name] (header through its last option line). */
    private fun sectionLineRange(lines: List<String>, name: String): IntRange? {
        val header = "[$name]"
        val start = lines.indexOfFirst { it.trim() == header }
        if (start < 0) return null
        var end = start
        for (i in start + 1 until lines.size) {
            val t = lines[i].trim()
            if (t.startsWith("[")) break
            // Trailing comments belong to whatever follows (usually the next section).
            if (t.isNotEmpty() && !t.startsWith("#") && !t.startsWith(";")) end = i
        }
        return start..end
    }

    private fun trailingBlank(lines: List<String>, range: IntRange): List<String> =
        if (range.last + 1 < lines.size && lines[range.last + 1].isNotBlank()) listOf("") else emptyList()

    private const val MAX_REMOTE_NAME = 128
}
