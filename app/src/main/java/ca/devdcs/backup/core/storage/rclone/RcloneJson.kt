package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.storage.RemoteEntry
import ca.devdcs.backup.core.storage.SpaceInfo
import ca.devdcs.backup.core.storage.StorageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Parsers for rclone's machine-readable output (`lsjson`, `lsjson --stat`, `about --json`).
 *
 * Exists so the session code stays free of JSON plumbing and the formats are pinned by unit tests.
 * Malformed output is a PROTOCOL failure (most often a mismatched rclone version).
 */
object RcloneJson {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parse `rclone lsjson <dir>` output. Entry paths are [parentPath]-relative names joined onto
     * [parentPath] so they are relative to the destination base, as the storage contract requires.
     */
    fun parseList(output: String, parentPath: String): List<RemoteEntry> {
        val root = parse(output, "lsjson")
        val array = root as? JsonArray
            ?: throw StorageException(StorageException.Reason.PROTOCOL, "rclone lsjson did not return a JSON array")
        val parent = RclonePaths.normalize(parentPath)
        return array.map { element ->
            val obj = element as? JsonObject
                ?: throw StorageException(StorageException.Reason.PROTOCOL, "rclone lsjson entry is not an object")
            val name = obj.string("Name") ?: obj.string("Path")?.substringAfterLast('/')
                ?: throw StorageException(StorageException.Reason.PROTOCOL, "rclone lsjson entry has no Name")
            toEntry(obj, name, RclonePaths.join(parent, name))
        }
    }

    /** Parse `rclone lsjson --stat <path>` output (one object) into an entry at [path]. */
    fun parseStat(output: String, path: String): RemoteEntry {
        val obj = parse(output, "lsjson --stat") as? JsonObject
            ?: throw StorageException(StorageException.Reason.PROTOCOL, "rclone lsjson --stat did not return a JSON object")
        val normalized = RclonePaths.normalize(path)
        val name = obj.string("Name")?.takeIf { it.isNotEmpty() } ?: normalized.substringAfterLast('/')
        return toEntry(obj, name, normalized)
    }

    /** Parse `rclone about --json`. Missing fields stay null (many backends report only some). */
    fun parseAbout(output: String): SpaceInfo {
        val obj = parse(output, "about --json") as? JsonObject
            ?: throw StorageException(StorageException.Reason.PROTOCOL, "rclone about did not return a JSON object")
        val total = obj.long("total")
        val used = obj.long("used")
        val free = obj.long("free") ?: if (total != null && used != null) (total - used).coerceAtLeast(0) else null
        return SpaceInfo(freeBytes = free, totalBytes = total)
    }

    /** RFC 3339 (with optional nanoseconds) → epoch millis; null when absent or unparsable. */
    fun parseModTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun toEntry(obj: JsonObject, name: String, path: String): RemoteEntry {
        val isDir = obj["IsDir"]?.jsonPrimitive?.booleanOrNull ?: false
        val size = obj.long("Size")?.takeIf { it >= 0 && !isDir }
        return RemoteEntry(
            path = path,
            name = name,
            isDirectory = isDir,
            sizeBytes = size,
            modifiedAtMillis = parseModTime(obj.string("ModTime")),
        )
    }

    private fun parse(output: String, what: String): JsonElement = try {
        json.parseToJsonElement(output.trim().ifEmpty { "null" })
    } catch (e: IllegalArgumentException) {
        throw StorageException(StorageException.Reason.PROTOCOL, "rclone $what returned malformed JSON", e)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}

/**
 * '/'-separated path helpers shared by the session and remote-spec builder. Rejects `..` so a
 * destination can never escape its configured base path.
 */
object RclonePaths {

    /** Strip leading/trailing/duplicate slashes; `""` and `"/"` become `""`. Throws on `..`. */
    fun normalize(path: String?): String {
        if (path.isNullOrEmpty()) return ""
        val parts = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) {
            throw StorageException(StorageException.Reason.MISCONFIGURED, "Path must not contain '..': $path")
        }
        return parts.joinToString("/")
    }

    /** Join normalized segments, skipping empty ones. */
    fun join(vararg parts: String?): String = parts.map { normalize(it) }.filter { it.isNotEmpty() }.joinToString("/")
}
