package ca.devdcs.backup.companion

import android.content.Context
import ca.devdcs.backup.BuildConfig
import ca.devdcs.backup.core.logging.DftbLog
import ca.devdcs.backup.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Assembles a flashable Magisk/KernelSU module ZIP entirely in-app.
 *
 * It exists so a device owner can turn the CLI tools DFTB already ships (packaged as `lib<tool>.so`
 * in `nativeLibraryDir`) into a systemless `/system/bin` overlay plus a reboot-resume boot service,
 * without downloading anything or trusting a prebuilt zip. The output is a standard module: a
 * `module.prop`, the install scripts under `META-INF/com/google/android/` using the documented
 * `#MAGISK` sentinel convention, the lifecycle scripts (`post-fs-data.sh` / `service.sh` /
 * `customize.sh` / `uninstall.sh`) rendered from asset templates, and one `system/bin/<tool>` per
 * bundled tool.
 *
 * Which tools to bundle is passed in by the caller (the native-tool layer decides what this ABI
 * actually has); the builder resolves each id to `nativeLibraryDir/lib<id>.so` itself. The `tools`
 * list is authoritative — a tool not requested is never searched for.
 *
 * ### Executable bits
 * `java.util.zip` cannot record POSIX permission bits, so every file lands non-executable when the
 * module is flashed. The generated `customize.sh` fixes this at install time with
 * `set_perm_recursive "$MODPATH/system/bin" 0 0 0755 0755`, which is also how Magisk itself expects
 * module binaries to be made executable.
 *
 * ### Determinism
 * Entries are written in a fixed order with a fixed, time-zone-independent local timestamp and
 * forward-slash names with no leading `./`, and the scripts embed only the app version (never the
 * wall clock), so building the same inputs twice yields a byte-identical zip (easier to verify and
 * to diff).
 */
@Singleton
class CompanionModuleBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Build the module zip into [outFile], bundling the available subset of [tools].
     *
     * Never throws for the ordinary failure modes: a tool absent from this ABI becomes a
     * [ModuleBuildResult.missingTools] entry, an unreadable template becomes a warning. Only a hard
     * failure to open/write [outFile] yields a result with a null [ModuleBuildResult.path].
     *
     * @param outFile destination zip (parent dirs are created; an existing file is overwritten).
     * @param tools canonical tool ids to attempt to bundle, resolved as `nativeLibraryDir/lib<id>.so`;
     * null means the manifest's [ModuleManifest.bundledToolIds].
     * @param request identity, compression and boot-service options.
     */
    suspend fun build(
        outFile: File,
        tools: List<String>? = null,
        request: ModuleBuildRequest = ModuleBuildRequest(),
    ): ModuleBuildResult = withContext(ioDispatcher) {
        val warnings = mutableListOf<String>()
        val manifest = request.manifest
        val requested = (tools ?: manifest.bundledToolIds).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        // Resolve every requested tool against nativeLibraryDir before opening the zip, so a
        // partial toolset is known up front and the summary scripts can list the real contents.
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val resolved = LinkedHashMap<String, File>()
        val missing = mutableListOf<String>()
        for (tool in requested) {
            val soName = "lib$tool.so"
            val soFile = File(nativeLibDir, soName)
            if (soFile.isFile && soFile.canRead()) {
                resolved[tool] = soFile
            } else {
                missing += tool
                warnings += "Tool '$tool' not bundled: $soName absent from this ABI's native library dir"
            }
        }
        if (resolved.isEmpty()) {
            warnings += "No requested tools were available; the module will install only scripts"
        }

        val tokens = substitutionTokens(manifest, resolved.keys.toList(), request)

        try {
            outFile.parentFile?.mkdirs()
            if (outFile.exists()) outFile.delete()

            ZipOutputStream(outFile.outputStream().buffered()).use { zip ->
                zip.setLevel(if (request.compress) Deflater.BEST_COMPRESSION else Deflater.NO_COMPRESSION)

                // 1. Installer scaffold (fixed Magisk boilerplate, generated in code).
                writeEntry(zip, "META-INF/com/google/android/update-binary", UPDATE_BINARY.toByteArray(), request.compress)
                writeEntry(zip, "META-INF/com/google/android/updater-script", UPDATER_SCRIPT.toByteArray(), request.compress)

                // 2. module.prop rendered from its template, with values from the manifest. If the
                //    template asset is somehow unreadable, fall back to the manifest's own emitter so
                //    the module is never shipped without a valid module.prop.
                val moduleProp = renderTemplate("companion/module.prop.template", tokens, warnings)
                    ?: manifest.toModuleProp()
                writeEntry(zip, "module.prop", moduleProp.toByteArray(), request.compress)

                // 3. Lifecycle scripts from asset templates, in a fixed order.
                for (asset in SCRIPT_ASSETS) {
                    val rendered = renderTemplate(asset.assetPath, tokens, warnings)
                    if (rendered != null) {
                        writeEntry(zip, asset.entryName, rendered.toByteArray(), request.compress)
                    }
                }

                // 4. system/bin/<tool>, tools in deterministic (sorted) order.
                for (tool in resolved.keys.sorted()) {
                    val soFile = resolved.getValue(tool)
                    writeEntry(zip, "system/bin/$tool", soFile.readBytes(), request.compress)
                }
            }

            val size = outFile.length()
            DftbLog.i(
                TAG,
                "Built companion module ${outFile.name}: $size bytes, " +
                    "tools=${resolved.keys.joinToString(",").ifEmpty { "none" }}, missing=${missing.joinToString(",").ifEmpty { "none" }}",
            )
            ModuleBuildResult(
                path = outFile,
                sizeBytes = size,
                includedTools = resolved.keys.toList(),
                missingTools = missing,
                warnings = warnings,
            )
        } catch (error: Exception) {
            DftbLog.e(TAG, "Failed to assemble companion module at ${outFile.absolutePath}", error)
            warnings += "Module assembly failed: ${error.message ?: error.javaClass.simpleName}"
            runCatching { if (outFile.exists()) outFile.delete() }
            ModuleBuildResult(
                path = null,
                sizeBytes = 0,
                includedTools = emptyList(),
                missingTools = requested,
                warnings = warnings,
            )
        }
    }

    /** Values substituted into every `{{TOKEN}}` in the asset templates and the installer scaffold. */
    private fun substitutionTokens(
        manifest: ModuleManifest,
        includedTools: List<String>,
        request: ModuleBuildRequest,
    ): Map<String, String> = mapOf(
        "MODULE_ID" to manifest.id,
        "MODULE_NAME" to manifest.name,
        "VERSION_NAME" to manifest.versionName,
        "VERSION_CODE" to manifest.versionCode.toString(),
        "AUTHOR" to manifest.author,
        "DESCRIPTION" to manifest.description,
        "MIN_MAGISK" to manifest.minMagiskVersionCode.toString(),
        "MIN_MAGISK_NOTE" to manifest.minMagiskNote,
        "MIN_KERNELSU_NOTE" to manifest.minKernelSuNote,
        "TOOL_LIST" to includedTools.sorted().joinToString(" ").ifEmpty { "(none)" },
        "DATA_ROOT" to ModuleManifest.DATA_ROOT,
        "JOURNAL_DIR" to ModuleManifest.JOURNAL_DIR,
        "RESUME_TRIGGER_FILE" to ModuleManifest.RESUME_TRIGGER_FILE,
        "SERVICE_LOG" to ModuleManifest.SERVICE_LOG,
        "MODULE_DIR" to ModuleManifest.MODULE_DIR,
        "APP_ID" to BuildConfig.APPLICATION_ID,
        "RESUME_ACTION" to BootResumeTrigger.ACTION_RESUME_JOBS,
        "RESUME_COMPONENT" to "${BuildConfig.APPLICATION_ID}/${BootResumeTrigger.COMPONENT_CLASS}",
        "BOOT_RESUME_ENABLED" to if (request.enableBootResume) "1" else "0",
        "USE_GLOBAL_NS" to if (request.useGlobalMountNamespace) "1" else "0",
        "APP_VERSION" to BuildConfig.VERSION_NAME,
    )

    /** Read an asset template and apply [tokens]; returns null (with a warning) if it cannot be read. */
    private fun renderTemplate(
        assetPath: String,
        tokens: Map<String, String>,
        warnings: MutableList<String>,
    ): String? = try {
        val raw = context.assets.open(assetPath).bufferedReader().use { it.readText() }
        // Scripts must use LF; normalise in case an editor introduced CRLF.
        substitute(raw.replace("\r\n", "\n"), tokens)
    } catch (error: Exception) {
        DftbLog.w(TAG, "Template $assetPath unavailable; skipping", error)
        warnings += "Template '$assetPath' could not be read and was skipped"
        null
    }

    /** Replace every `{{KEY}}` with its token value; unknown placeholders are left untouched. */
    private fun substitute(template: String, tokens: Map<String, String>): String {
        var result = template
        for ((key, value) in tokens) {
            result = result.replace("{{$key}}", value)
        }
        return result
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray, compress: Boolean) {
        val entry = ZipEntry(name).apply {
            timeLocal = FIXED_ENTRY_TIME
            if (!compress) {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                crc = java.util.zip.CRC32().apply { update(bytes) }.value
            } else {
                method = ZipEntry.DEFLATED
            }
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private data class ScriptAsset(val assetPath: String, val entryName: String)

    companion object {
        private const val TAG = "CompanionBuild"

        /**
         * Fixed entry timestamp (2021-01-01 00:00, local zip time). Set via `timeLocal` so the DOS
         * time fields do not depend on the device's time zone, keeping archives reproducible.
         */
        private val FIXED_ENTRY_TIME: LocalDateTime = LocalDateTime.of(2021, 1, 1, 0, 0)

        private val SCRIPT_ASSETS = listOf(
            ScriptAsset("companion/customize.sh", "customize.sh"),
            ScriptAsset("companion/post-fs-data.sh", "post-fs-data.sh"),
            ScriptAsset("companion/service.sh", "service.sh"),
            ScriptAsset("companion/uninstall.sh", "uninstall.sh"),
        )

        /**
         * Standard Magisk module `update-binary`: a shell stub that loads `util_functions.sh` and
         * calls `install_module`, which in turn sources `customize.sh`. This is the documented,
         * long-stable convention shared by Magisk, KernelSU and APatch.
         */
        private val UPDATE_BINARY = """
            #!/sbin/sh

            #################
            # Initialization
            #################

            umask 022

            # Robust ui_print until util_functions is loaded
            ui_print() { echo "${'$'}1"; }

            require_new_magisk() {
              ui_print "*******************************"
              ui_print " Please install Magisk v20.4+! "
              ui_print "*******************************"
              exit 1
            }

            #########################
            # Load util_functions.sh
            #########################

            OUTFD=${'$'}2
            ZIPFILE=${'$'}3

            mount /data 2>/dev/null

            [ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
            . /data/adb/magisk/util_functions.sh
            [ ${'$'}MAGISK_VER_CODE -lt 20400 ] && require_new_magisk

            install_module
            exit 0
        """.trimIndent() + "\n"

        /** The sentinel updater-script every modern Magisk module ships. */
        private val UPDATER_SCRIPT = "#MAGISK\n"
    }
}
