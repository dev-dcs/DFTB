package ca.devdcs.backup.core.storage.rclone

import ca.devdcs.backup.core.storage.StorageException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns an rclone failure (exit code + `--use-json-log` stderr) into a [StorageException.Reason].
 *
 * Exists because rclone reports every backend's errors as free text; the UI and retry policy need
 * a uniform reason. Pure so the keyword table is unit-tested.
 */
object RcloneErrorMapper {

    /**
     * One parsed rclone log record. Non-JSON stderr lines become level "raw". [objectName] (the file
     * the record is about) is kept apart so a file name never drives classification.
     */
    data class LogRecord(val level: String, val message: String, val objectName: String? = null) {
        /** Message with the object name prefixed, for display. */
        val display: String get() = if (objectName.isNullOrEmpty() || message.contains(objectName)) message else "$objectName: $message"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** rclone exit codes (see `rclone help flags` / docs "Exit Code"). */
    const val EXIT_DIRECTORY_NOT_FOUND = 3
    const val EXIT_FILE_NOT_FOUND = 4

    private val AUTH_STATUS = Regex("\\b(401|403)\\b")
    private val NO_SPACE_STATUS = Regex("\\b507\\b")

    /** Parse stderr lines; JSON records keep their level/msg, plain lines are kept verbatim. */
    fun parseLog(lines: List<String>): List<LogRecord> = lines.mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@mapNotNull null
        if (line.startsWith("{")) {
            val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull()
            if (obj != null) {
                val level = obj["level"]?.jsonPrimitive?.contentOrNull ?: "info"
                val msg = obj["msg"]?.jsonPrimitive?.contentOrNull ?: ""
                val objectName = obj["object"]?.jsonPrimitive?.contentOrNull
                return@mapNotNull LogRecord(level.lowercase(), msg, objectName)
            }
        }
        LogRecord("raw", line)
    }

    /** Classify a message (or concatenation of messages). Null when no keyword matches. */
    fun classify(message: String): StorageException.Reason? {
        val m = message.lowercase()
        return when {
            "quota" in m || "insufficient storage" in m || "insufficient space" in m || "no space left" in m ||
                "storage limit" in m || NO_SPACE_STATUS.containsMatchIn(m) -> StorageException.Reason.NO_SPACE
            "invalid_grant" in m || "invalid_client" in m || AUTH_STATUS.containsMatchIn(m) || "unauthorized" in m ||
                "accessdenied" in m || "access denied" in m || "invalidaccesskeyid" in m ||
                "signaturedoesnotmatch" in m || "login failed" in m || "authentication failed" in m ||
                "bad token" in m || "token expired" in m || "unable to authenticate" in m ||
                "permission denied (publickey" in m -> StorageException.Reason.AUTH
            "directory not found" in m || "object not found" in m || "file not found" in m ||
                "nosuchbucket" in m || "nosuchkey" in m || "not found" in m && "config" !in m && "section" !in m ->
                StorageException.Reason.NOT_FOUND
            "couldn't connect" in m || "could not connect" in m || "connection refused" in m ||
                "no such host" in m || "network is unreachable" in m || "connection reset" in m ||
                "dial tcp" in m || "timeout" in m || "timed out" in m || "deadline exceeded" in m ||
                "tls handshake" in m || "eof" == m.trim() -> StorageException.Reason.NETWORK
            "didn't find section in config file" in m || "config file not found" in m ||
                "unknown backend" in m || "unknown option" in m || "failed to create file system" in m &&
                "couldn't" !in m -> StorageException.Reason.MISCONFIGURED
            "doesn't support" in m || "not supported" in m || "can't" in m && "seek" in m ->
                StorageException.Reason.UNSUPPORTED
            "permission denied" in m || "read-only file system" in m -> StorageException.Reason.PERMISSION
            else -> null
        }
    }

    /**
     * Build the exception for a failed invocation. Exit codes 3/4 (directory/file not found) are
     * authoritative; otherwise error/critical records (message only) are classified by keyword,
     * preferring them over notices, with PROTOCOL as the fallback.
     */
    fun toException(operation: String, exitCode: Int, logLines: List<String>): StorageException {
        val records = parseLog(logLines)
        val significant = records.filter { it.level == "error" || it.level == "critical" || it.level == "fatal" || it.level == "raw" }
            .ifEmpty { records }
        val reason = when (exitCode) {
            EXIT_DIRECTORY_NOT_FOUND, EXIT_FILE_NOT_FOUND -> StorageException.Reason.NOT_FOUND
            else -> significant.firstNotNullOfOrNull { classify(it.message) } ?: StorageException.Reason.PROTOCOL
        }
        val detail = significant.lastOrNull()?.display?.take(MAX_DETAIL) ?: "no error output"
        return StorageException(reason, "rclone $operation failed (exit $exitCode): $detail")
    }

    /** True when the failure only means the path does not exist. */
    fun isNotFound(exitCode: Int, logLines: List<String>): Boolean =
        toException("probe", exitCode, logLines).reason == StorageException.Reason.NOT_FOUND

    private const val MAX_DETAIL = 400
}
