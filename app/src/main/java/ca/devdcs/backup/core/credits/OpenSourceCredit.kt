package ca.devdcs.backup.core.credits

import androidx.annotation.StringRes
import ca.devdcs.backup.R

/**
 * How a credited project relates to the DFTB APK. Drives the section grouping in the credits UI
 * and tells the user whether the project's code is actually on their device or merely a format
 * DFTB can read and write.
 */
enum class BundleKind(@StringRes val labelRes: Int) {
    /** CLI tool packaged as `lib<name>.so` in the APK and executed from `nativeLibraryDir`. */
    NATIVE_BINARY(R.string.bundle_kind_native_binary),

    /** Plain JVM/Kotlin dependency compiled into the app's dex. */
    JVM_LIBRARY(R.string.bundle_kind_jvm_library),

    /** Android-specific library (AAR) compiled into the app, usually with resources or manifests. */
    ANDROID_LIBRARY(R.string.bundle_kind_android_library),

    /** Not shipped: DFTB reads, writes or talks to this project's formats or interfaces. */
    FORMAT_COMPATIBILITY(R.string.bundle_kind_format_compatibility),

    /** Used only to build the APK; no code from it ends up on the device. */
    BUILD_TOOLCHAIN(R.string.bundle_kind_build_toolchain),
}

/**
 * One row in the open-source credits list. Exists so license attribution is data, not prose, and
 * can be rendered identically in onboarding and in Settings > About.
 *
 * @property id Stable snake_case key; string resources for this entry are `credit_<id>_desc`.
 * @property name Project name as the upstream spells it (not translated).
 * @property descriptionRes Localized 1–2 sentence description of the project and DFTB's use of it.
 * @property license The project's license.
 * @property sourceUrl Canonical source repository.
 * @property websiteUrl Project home page, if distinct from the repository.
 * @property bundledAs Relationship between the project and the APK.
 * @property version Version DFTB bundles or targets; null when not pinned yet or not applicable.
 */
data class OpenSourceCredit(
    val id: String,
    val name: String,
    @StringRes val descriptionRes: Int,
    val license: OssLicense,
    val sourceUrl: String,
    val websiteUrl: String?,
    val bundledAs: BundleKind,
    val version: String?,
)
