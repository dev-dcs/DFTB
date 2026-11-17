package ca.devdcs.backup.companion

import java.io.File

/**
 * Inputs for one run of [CompanionModuleBuilder.build], kept separate from the tool list and output
 * file so new build options can be added without reshaping the builder's signature.
 *
 * It exists because assembling a flashable module is a user-initiated action with a few knobs (which
 * module identity to stamp, whether to compress, whether the late-boot service should attempt to
 * wake the app) and those knobs are easier to evolve as a single value object than as positional
 * parameters.
 */
data class ModuleBuildRequest(
    /** Identity + version + tool catalogue stamped into `module.prop` and the script templates. */
    val manifest: ModuleManifest = ModuleManifest.current(),
    /** DEFLATE the zip entries (smaller artifact) vs. STORE them (faster, larger). */
    val compress: Boolean = true,
    /**
     * Have the generated `service.sh` broadcast [ca.devdcs.backup.companion.BootResumeTrigger] so the
     * app re-enqueues its resume worker on late boot. Off produces a tools-only module with no
     * app-wake behaviour.
     */
    val enableBootResume: Boolean = true,
    /**
     * Enter the global (init) mount namespace via `nsenter -t 1 -m` inside `service.sh` before
     * touching `/data`, matching [ca.devdcs.backup.core.exec.Command.requireGlobalMountNamespace] so
     * the module sees the same `/data` the engines snapshot. Documented in the script either way.
     */
    val useGlobalMountNamespace: Boolean = true,
)

/**
 * Outcome of [CompanionModuleBuilder.build]. The builder is deliberately non-throwing for the
 * expected failure modes (a tool missing from this ABI, an unreadable asset); those surface here as
 * [missingTools] and [warnings] so the UI can present a partial success instead of an exception.
 */
data class ModuleBuildResult(
    /** The assembled zip. Present even on partial success; absent only on a hard I/O failure. */
    val path: File?,
    val sizeBytes: Long,
    /** Tool ids whose `lib<id>.so` was found and written to `system/bin/<id>`. */
    val includedTools: List<String>,
    /** Tool ids requested but not present in this build's `nativeLibraryDir`. */
    val missingTools: List<String>,
    /** Non-fatal problems (missing tools, skipped templates, I/O warnings) for the UI/log. */
    val warnings: List<String>,
) {
    /** A usable zip was written to disk, whether or not every requested tool made it in. */
    val produced: Boolean get() = path != null && sizeBytes > 0

    /** The zip was produced and every requested tool is in it. */
    val complete: Boolean get() = produced && missingTools.isEmpty()
}
