package ca.devdcs.backup.companion

import ca.devdcs.backup.BuildConfig

/**
 * Immutable description of the systemless companion module DFTB can assemble and hand to Magisk or
 * KernelSU.
 *
 * It exists so the module's identity (what shows in the root manager, which tools it bundles, where
 * it keeps its journal) lives in one typed place instead of being scattered through shell-script
 * string literals. [CompanionModuleBuilder] turns it into `module.prop` and into the `{{TOKEN}}`
 * substitutions used by the asset script templates, and [ca.devdcs.backup.core.boot.JobResumeWorker]
 * reuses the path constants to find the reboot-resume journal. Versioning is pinned to the app's own
 * [BuildConfig] so a freshly built module can never claim a version the app does not ship.
 */
data class ModuleManifest(
    /** Magisk module id. Must match `[a-zA-Z][a-zA-Z0-9._-]*`; also the `/data/adb/modules` folder name. */
    val id: String,
    /** Human-readable name shown in the root manager's module list. */
    val name: String,
    /** Version string shown to the user (the app's own versionName, prefixed `v` in `module.prop`). */
    val versionName: String,
    /** Monotonic version code the root manager compares for upgrades. */
    val versionCode: Int,
    val author: String,
    /** One-line summary written into `module.prop`'s `description` field. */
    val description: String,
    /** Canonical ids of the CLI tools this module is meant to carry (e.g. `busybox`, `tar`). */
    val bundledToolIds: List<String>,
    /** Lowest Magisk versionCode that understands the module format this builder emits. */
    val minMagiskVersionCode: Int,
    /** Human note about Magisk compatibility, surfaced in the builder UI. */
    val minMagiskNote: String,
    /** Human note about KernelSU compatibility (KernelSU consumes the same module format). */
    val minKernelSuNote: String,
) {

    /**
     * The exact `module.prop` body (LF-terminated, no trailing blank line) for this manifest. The
     * field set and order follow Magisk's documented format: `id`, `name`, `version`, `versionCode`,
     * `author`, `description`.
     */
    fun toModuleProp(): String = buildString {
        append("id=").append(id).append('\n')
        append("name=").append(name).append('\n')
        append("version=v").append(versionName).append('\n')
        append("versionCode=").append(versionCode).append('\n')
        append("author=").append(author).append('\n')
        append("description=").append(sanitizeProp(description)).append('\n')
    }

    /** `module.prop` values are single-line `key=value`; strip anything that would break that. */
    private fun sanitizeProp(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim()

    companion object {
        const val MODULE_ID = "dftb_companion"
        const val MODULE_NAME = "DFTB Companion"
        const val AUTHOR = "DFTB (Don't Forget To Backup)"

        /** Root of DFTB's root-visible state; created by the module, owned by uid 0, mode 0700. */
        const val DATA_ROOT = "/data/adb/dftb"

        /** Where interrupted jobs flagged resume-after-reboot drop their JSON state files. */
        const val JOURNAL_DIR = "$DATA_ROOT/journal"

        /** Marker the late-boot service writes when it has seen resumable work; cleared on uninstall. */
        const val RESUME_TRIGGER_FILE = "$DATA_ROOT/resume.trigger"

        /** Boot/service log the module appends to; readable only with root. */
        const val SERVICE_LOG = "$DATA_ROOT/service.log"

        const val MODULES_DIR = "/data/adb/modules"
        const val MODULE_DIR = "$MODULES_DIR/$MODULE_ID"

        /**
         * Magisk 20.4 (versionCode 20400) is the floor for the `install_module` + `customize.sh`
         * convention this builder emits; older Magisk uses an incompatible installer contract.
         */
        const val MIN_MAGISK_VERSION_CODE = 20400

        /**
         * Tools the module carries by default. Kept in lockstep with the ids
         * [ca.devdcs.backup.core.environment.CompanionModuleProbe] looks for under `/system/bin`, so
         * a module built here satisfies the Tier 4 diagnostics that check for them.
         */
        val DEFAULT_TOOL_IDS: List<String> = listOf("busybox", "nsenter", "tar", "zstd", "rclone", "dftbtool")

        /**
         * The manifest for the module this build of the app produces. All version data comes from
         * [BuildConfig] so the module and the app can never disagree about their version.
         */
        fun current(): ModuleManifest = ModuleManifest(
            id = MODULE_ID,
            name = MODULE_NAME,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            author = AUTHOR,
            description = "Systemless CLI tools and a reboot-resume boot service for the " +
                "${BuildConfig.APP_DISPLAY_NAME} backup app. User-installed; removable from the root manager.",
            bundledToolIds = DEFAULT_TOOL_IDS,
            minMagiskVersionCode = MIN_MAGISK_VERSION_CODE,
            minMagiskNote = "Requires Magisk v20.4 or newer.",
            minKernelSuNote = "Compatible with KernelSU and APatch (same Magisk module format).",
        )
    }
}
