package ca.devdcs.backup.core.storage.rclone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Validates the token JSON a user pastes after running `rclone authorize "<type>"` on a desktop.
 *
 * Exists because OAuth providers cannot be authorized on-device without a browser redirect flow
 * DFTB does not implement; the desktop flow is rclone's documented headless path, and a typo in a
 * pasted token otherwise only surfaces at the first backup. Accepts the whole terminal block
 * (including rclone's `Paste the following ... --->` / `<---End paste` markers) and returns a
 * compact single-line JSON suitable as an rclone.conf `token =` value.
 */
object OAuthTokenValidator {

    sealed interface Result {
        /** [normalizedJson] is single-line; [hasRefreshToken] false means it will stop working at [expiry]. */
        data class Valid(val normalizedJson: String, val hasRefreshToken: Boolean, val expiry: String?) : Result
        data class Invalid(val problem: Problem) : Result
    }

    enum class Problem {
        EMPTY,
        NOT_JSON,
        MISSING_ACCESS_TOKEN,
        MISSING_TOKEN_TYPE,
        BAD_EXPIRY,
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun validate(input: String): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.Invalid(Problem.EMPTY)
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return Result.Invalid(Problem.NOT_JSON)
        val element = try {
            json.parseToJsonElement(trimmed.substring(start, end + 1))
        } catch (_: IllegalArgumentException) {
            return Result.Invalid(Problem.NOT_JSON)
        }
        val obj = element as? JsonObject ?: return Result.Invalid(Problem.NOT_JSON)
        val access = obj.string("access_token")
        if (access.isNullOrBlank()) return Result.Invalid(Problem.MISSING_ACCESS_TOKEN)
        if (obj.string("token_type").isNullOrBlank()) return Result.Invalid(Problem.MISSING_TOKEN_TYPE)
        val expiry = obj.string("expiry")
        if (!expiry.isNullOrBlank() && !isRfc3339(expiry)) return Result.Invalid(Problem.BAD_EXPIRY)
        return Result.Valid(
            normalizedJson = json.encodeToString(JsonObject.serializer(), obj),
            hasRefreshToken = !obj.string("refresh_token").isNullOrBlank(),
            expiry = expiry,
        )
    }

    private fun isRfc3339(value: String): Boolean = try {
        OffsetDateTime.parse(value)
        true
    } catch (_: DateTimeParseException) {
        // Go's zero time ("0001-01-01T00:00:00Z") parses fine; anything else unparsable is rejected.
        false
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
