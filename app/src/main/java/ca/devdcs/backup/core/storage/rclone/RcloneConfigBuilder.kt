package ca.devdcs.backup.core.storage.rclone

/**
 * Turns a [ProviderGuide] plus the user's field values into an rclone.conf section.
 *
 * Exists so guided setup produces exactly what `rclone config` would write (type first, fixed
 * provider options, obscured passwords, single-line token JSON, `\n`-escaped PEM keys) and rejects
 * bad input per field before anything reaches the config. Pure: obscuring is delegated to the
 * supplied function (in production [RcloneCli.obscure]), so it is unit-tested with a fake.
 */
object RcloneConfigBuilder {

    /** A problem with one field ([key]) or with the remote name ([key] == [REMOTE_NAME_KEY]). */
    data class FieldError(
        val key: String,
        val problem: Problem,
        val tokenProblem: OAuthTokenValidator.Problem? = null,
    )

    enum class Problem {
        REQUIRED,
        INVALID_REMOTE_NAME,
        NOT_A_NUMBER,
        NOT_A_URL,
        NOT_AN_EMAIL,
        NOT_A_CHOICE,
        LINE_BREAK,
        TOKEN_INVALID,
        NEED_ONE_OF,
        UNKNOWN_FIELD,
    }

    enum class Warning {
        /** Pasted token has no refresh token, so the remote stops working when it expires. */
        TOKEN_WITHOUT_REFRESH,
    }

    sealed interface Result {
        data class Success(val section: RcloneSection, val warnings: List<Warning>) : Result {
            val iniText: String get() = RcloneIni.render(section)
        }
        data class Failure(val errors: List<FieldError>) : Result
    }

    const val REMOTE_NAME_KEY = "@remote_name"

    private val URL = Regex("^https?://[^\\s/]+.*$", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val NUMBER = Regex("^[0-9]{1,9}$")

    /** Field validation only (no obscuring); empty when [build] would succeed. */
    fun validate(guide: ProviderGuide, remoteName: String, values: Map<String, String>): List<FieldError> {
        val errors = mutableListOf<FieldError>()
        if (!RcloneIni.isValidRemoteName(remoteName)) errors += FieldError(REMOTE_NAME_KEY, Problem.INVALID_REMOTE_NAME)
        values.keys.filter { guide.field(it) == null }.forEach { errors += FieldError(it, Problem.UNKNOWN_FIELD) }
        guide.fields.forEach { field ->
            val value = effectiveValue(field, values)
            if (value.isEmpty()) {
                if (field.required) errors += FieldError(field.key, Problem.REQUIRED)
                return@forEach
            }
            fieldProblem(field, value)?.let { errors += it }
        }
        guide.atLeastOneOf.forEach { group ->
            val anyFilled = group.any { key -> guide.field(key)?.let { effectiveValue(it, values).isNotEmpty() } == true }
            if (!anyFilled) group.forEach { errors += FieldError(it, Problem.NEED_ONE_OF) }
        }
        return errors
    }

    /** Validate and build. [obscure] is applied to every field with [GuideField.obscure]. */
    suspend fun build(
        guide: ProviderGuide,
        remoteName: String,
        values: Map<String, String>,
        obscure: suspend (String) -> String,
    ): Result {
        val errors = validate(guide, remoteName, values)
        if (errors.isNotEmpty()) return Result.Failure(errors)
        val warnings = mutableListOf<Warning>()
        val options = LinkedHashMap<String, String>()
        options["type"] = guide.rcloneType
        options.putAll(guide.fixedOptions)
        for (field in guide.fields) {
            val value = effectiveValue(field, values)
            if (value.isEmpty()) continue
            options[field.key] = when {
                field.kind == GuideFieldKind.OAUTH_TOKEN -> {
                    val valid = OAuthTokenValidator.validate(value) as OAuthTokenValidator.Result.Valid
                    if (!valid.hasRefreshToken) warnings += Warning.TOKEN_WITHOUT_REFRESH
                    valid.normalizedJson
                }
                field.kind == GuideFieldKind.MULTILINE_SECRET -> escapeNewlines(value)
                field.obscure -> obscure(value)
                else -> value
            }
        }
        return Result.Success(RcloneSection(remoteName, options), warnings)
    }

    /** PEM → rclone's single-line form (`-----BEGIN ...-----\n...`). */
    fun escapeNewlines(value: String): String =
        value.replace("\r\n", "\n").trim('\n').split('\n').joinToString("\\n") { it.trimEnd() }

    private fun effectiveValue(field: GuideField, values: Map<String, String>): String {
        val raw = values[field.key]
        // Secrets are kept verbatim except surrounding line breaks; plain fields are trimmed.
        val cleaned = if (field.secret) raw?.trim('\n', '\r') else raw?.trim()
        return cleaned?.takeIf { it.isNotEmpty() } ?: field.defaultValue.orEmpty()
    }

    private fun fieldProblem(field: GuideField, value: String): FieldError? {
        if (field.kind != GuideFieldKind.MULTILINE_SECRET && field.kind != GuideFieldKind.OAUTH_TOKEN &&
            (value.contains('\n') || value.contains('\r'))
        ) {
            return FieldError(field.key, Problem.LINE_BREAK)
        }
        return when (field.kind) {
            GuideFieldKind.NUMBER -> if (NUMBER.matches(value)) null else FieldError(field.key, Problem.NOT_A_NUMBER)
            GuideFieldKind.URL -> if (URL.matches(value)) null else FieldError(field.key, Problem.NOT_A_URL)
            GuideFieldKind.EMAIL -> if (EMAIL.matches(value)) null else FieldError(field.key, Problem.NOT_AN_EMAIL)
            GuideFieldKind.CHOICE -> if (value in field.choices) null else FieldError(field.key, Problem.NOT_A_CHOICE)
            GuideFieldKind.OAUTH_TOKEN -> when (val result = OAuthTokenValidator.validate(value)) {
                is OAuthTokenValidator.Result.Valid -> null
                is OAuthTokenValidator.Result.Invalid -> FieldError(field.key, Problem.TOKEN_INVALID, result.problem)
            }
            GuideFieldKind.TEXT, GuideFieldKind.PASSWORD, GuideFieldKind.MULTILINE_SECRET -> null
        }
    }
}
