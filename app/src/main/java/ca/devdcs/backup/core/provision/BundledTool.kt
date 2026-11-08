package ca.devdcs.backup.core.provision

import androidx.annotation.StringRes
import ca.devdcs.backup.R

/**
 * The capability area a [BundledTool] contributes to. Purely informational: it lets a diagnostics
 * or settings screen group tools by what they unlock ("compression", "remote sync") and lets the
 * engines in later phases ask "do I have anything that gives me COMPRESSION?" without hard-coding a
 * specific tool id. It is NOT a guarantee the tool is present — availability is decided at runtime
 * by [NativeToolProvisioner].
 */
enum class ToolFeature(@StringRes val labelRes: Int) {
    /** Transfer archives to/from cloud and network remotes. */
    REMOTE_SYNC(R.string.provision_feature_remote_sync),

    /** Pack and unpack POSIX tar streams. */
    ARCHIVING(R.string.provision_feature_archiving),

    /** Compress and decompress archive streams. */
    COMPRESSION(R.string.provision_feature_compression),

    /** Symmetric encryption/decryption of archives. */
    ENCRYPTION(R.string.provision_feature_encryption),

    /** General-purpose POSIX utilities with consistent flags across devices. */
    CORE_UTILITIES(R.string.provision_feature_core_utilities),

    /** Verify integrity (hashing) of produced or restored files. */
    INTEGRITY(R.string.provision_feature_integrity),
}

/**
 * The registry of command-line tools DFTB may ship inside the APK and run on-device.
 *
 * Each runnable binary is packaged per-ABI as `lib<id>.so` so the Android installer places it,
 * already executable for the app's uid, into `applicationInfo.nativeLibraryDir` (the project sets
 * `jniLibs.useLegacyPackaging = true` so the file is extracted on disk rather than left compressed
 * inside the APK). This enum is the single source of truth for which tools exist, what they are
 * for, and how their `.so` is named; [NativeToolProvisioner] turns an entry into a concrete
 * [ToolAvailability] by probing the filesystem and, when a shell is available, the environment.
 *
 * The large third-party prebuilts (rclone, busybox, zstd, xz, pigz, openssl, tar) are intentionally
 * NOT committed to the repository, so on a freshly built APK only [DFTBTOOL] — a tiny tool built
 * from source in this module — is guaranteed present. The provisioner is written to report the
 * absent ones honestly rather than assume they exist.
 */
enum class BundledTool(
    /** Stable, lowercase identifier used in logs, as the staged file name, and as [ToolAvailability.toolId]. */
    val id: String,
    /** The capability this tool contributes to, for informational grouping. */
    val feature: ToolFeature,
    /** User-visible one-line explanation of why this tool is bundled. */
    @StringRes val purposeRes: Int,
) {
    RCLONE("rclone", ToolFeature.REMOTE_SYNC, R.string.provision_tool_rclone_purpose),
    BUSYBOX("busybox", ToolFeature.CORE_UTILITIES, R.string.provision_tool_busybox_purpose),
    NSENTER("nsenter", ToolFeature.CORE_UTILITIES, R.string.provision_tool_nsenter_purpose),
    ZSTD("zstd", ToolFeature.COMPRESSION, R.string.provision_tool_zstd_purpose),
    XZ("xz", ToolFeature.COMPRESSION, R.string.provision_tool_xz_purpose),
    PIGZ("pigz", ToolFeature.COMPRESSION, R.string.provision_tool_pigz_purpose),
    OPENSSL("openssl", ToolFeature.ENCRYPTION, R.string.provision_tool_openssl_purpose),
    TAR("tar", ToolFeature.ARCHIVING, R.string.provision_tool_tar_purpose),

    /**
     * DFTB's own smoke-test tool, built from `app/src/main/cpp/dftbtool.c`. It proves the whole
     * extract-and-exec path works on the device (locate → stage → chmod → exec) without shipping a
     * multi-megabyte prebuilt, and provides a dependency-free SHA-256 / copy fallback.
     */
    DFTBTOOL("dftbtool", ToolFeature.INTEGRITY, R.string.provision_tool_dftbtool_purpose);

    /**
     * The per-ABI library file name the installer places in `nativeLibraryDir`, e.g. `librclone.so`.
     *
     * [ZSTD] is deliberately `libzstd_bin.so`, not `libzstd.so`: a JNI compression library could
     * also want the plain name (zstd-jni ships `libzstd-jni-*.so` today, but the `_bin` suffix keeps
     * our standalone CLI binary unambiguously separate from any real `.so` the linker might load).
     */
    val libFileName: String get() = when (this) {
        ZSTD -> "libzstd_bin.so"
        else -> "lib$id.so"
    }

    companion object {
        /** The registered tool with this [id], or null if [id] is not a known bundled tool. */
        fun fromId(id: String): BundledTool? = entries.firstOrNull { it.id == id }
    }
}
