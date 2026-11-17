package ca.devdcs.backup.core.provision

/**
 * Where a located copy of a tool was found. Ordered loosely from "cheapest / most trusted for the
 * app's own uid" to "requires a shell / foreign uid", which is also the order
 * [NativeToolProvisioner] prefers when deciding which copy to use.
 */
enum class ToolSource {
    /** `applicationInfo.nativeLibraryDir/lib<id>.so`: shipped in the APK, already executable by the app uid. */
    NATIVE_LIB_DIR,

    /** A copy staged by DFTB into a shared tmp directory (e.g. `/data/local/tmp`) so another uid can exec it. */
    STAGED_TMP,

    /** Resolved on the shell's `PATH` (typically `/system/bin` or `/system/xbin`). */
    SYSTEM_PATH,

    /** Provided by the systemless companion module's own `system/bin` tree. */
    COMPANION_MODULE,

    /** Not found anywhere reachable. */
    ABSENT,
}

/**
 * The outcome of looking for one tool. This is the currency every provisioning call speaks: the
 * engines in Phases 4/6/7 ask the provisioner for a tool and receive this, so they can decide
 * whether a feature is possible and, if so, which [path] to exec. It deliberately distinguishes
 * "found but not yet version-probed" ([version] == null) from "found and identified", because
 * probing a version costs a process launch that callers may not always want to pay.
 */
data class ToolAvailability(
    /** The [BundledTool.id] this result describes (kept as a String so unknown ids can still be reported). */
    val toolId: String,
    /** True only when a usable, executable copy was found at [path]. */
    val available: Boolean,
    /** Absolute path to the executable copy, or null when [source] is [ToolSource.ABSENT]. */
    val path: String?,
    /** How/where the copy was found. */
    val source: ToolSource,
    /** First line of the tool's `--version` output, or null if unprobed or the probe produced nothing. */
    val version: String?,
) {
    companion object {
        /** An "not found" result for [toolId]; the canonical value every failing lookup returns. */
        fun absent(toolId: String): ToolAvailability =
            ToolAvailability(toolId = toolId, available = false, path = null, source = ToolSource.ABSENT, version = null)
    }
}
