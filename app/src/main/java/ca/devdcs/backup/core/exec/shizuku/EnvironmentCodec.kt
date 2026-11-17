package ca.devdcs.backup.core.exec.shizuku

/**
 * Turns a [Command][ca.devdcs.backup.core.exec.Command]'s environment map into the flat
 * `KEY=VALUE` string array that crosses the Binder boundary to the Shizuku user service, and back.
 *
 * WHY a dedicated codec: AIDL has no `Map` type, so both sides must agree on one flat wire form.
 * Keeping the encode/decode in one pure-Kotlin object (no Android, no Binder) means the single
 * source of truth is unit-testable off-device and cannot drift between the app process (which
 * encodes) and the privileged process (which decodes onto a [ProcessBuilder] environment).
 */
object EnvironmentCodec {

    private const val SEPARATOR = '='

    /** Flatten [environment] to `KEY=VALUE` entries. Order is unspecified; keys are assumed non-empty. */
    fun encode(environment: Map<String, String>): Array<String> =
        environment.map { (key, value) -> "$key$SEPARATOR$value" }.toTypedArray()

    /**
     * Apply encoded [entries] onto [target] (typically `ProcessBuilder.environment()`), so the
     * command's overrides layer on top of the inherited process environment. Each entry is split on
     * its FIRST `=`, letting values contain `=` (e.g. a `PATH` or base64 token). Entries with an
     * empty key or no `=` are ignored rather than corrupting the environment.
     */
    fun applyTo(target: MutableMap<String, String>, entries: Array<String>) {
        for (entry in entries) {
            val separatorIndex = entry.indexOf(SEPARATOR)
            if (separatorIndex <= 0) continue
            val key = entry.substring(0, separatorIndex)
            val value = entry.substring(separatorIndex + 1)
            target[key] = value
        }
    }
}
