import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // Kotlin comes from AGP 9's built-in support; do not apply org.jetbrains.kotlin.android here.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val appId = providers.gradleProperty("dftb.applicationId").get()
val appNamespace = providers.gradleProperty("dftb.namespace").get()
val appDisplayName = providers.gradleProperty("dftb.appName").get()

/**
 * App version in a base-100 scheme: every edit adds 0.0.1 and a place rolls over at 100, so
 * 0.0.99 + 0.0.1 is written 0.1.0 (never 0.0.100). Single source of truth: bump only this line.
 */
val appVersionName = "0.8.2"

/** versionCode derived from the name so the two can never drift apart. */
val appVersionCode = appVersionName.split(".").map(String::toInt).let { parts ->
    require(parts.size == 3) { "appVersionName must be major.minor.patch, was \"$appVersionName\"" }
    require(parts.all { it in 0..99 }) { "each place rolls over at 100, was \"$appVersionName\"" }
    parts[0] * 10_000 + parts[1] * 100 + parts[2]
}

// --- Release signing: untracked keystore.properties or environment, never the debug key. ---------
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, envKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "DFTB_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "DFTB_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "DFTB_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "DFTB_KEY_PASSWORD")

val hasReleaseSigning = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null &&
    file(releaseStoreFile).exists()

android {
    namespace = appNamespace
    compileSdk = providers.gradleProperty("dftb.compileSdk").get().toInt()
    ndkVersion = providers.gradleProperty("dftb.ndkVersion").get()

    defaultConfig {
        applicationId = appId
        minSdk = providers.gradleProperty("dftb.minSdk").get().toInt()
        targetSdk = providers.gradleProperty("dftb.targetSdk").get().toInt()
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        resValue("string", "app_name", appDisplayName)
        buildConfigField("String", "APP_DISPLAY_NAME", "\"$appDisplayName\"")
        // Native CLI binaries (busybox, rclone, zstd...) are packaged as lib<name>.so per ABI so
        // they land executable in nativeLibraryDir. armeabi-v7a is included so the bundled
        // prebuilts also cover 32-bit-only devices; `scripts/fetch_prebuilts.sh` stages all three.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-fvisibility=hidden", "-Wall", "-Wextra")
                arguments += listOf("-DANDROID_STL=c++_static", "-DANDROID_PLATFORM=android-29")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = providers.gradleProperty("dftb.cmakeVersion").get()
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    androidResources {
        // Only the seven shipped UI languages; keeps library-only locales out of the APK and out
        // of the Android 13+ "App languages" picker.
        localeFilters += listOf("en", "fr", "es", "zh-rCN", "ja", "el", "ar")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
        // Compile src/main/aidl: the Shizuku cross-process contract (IShizukuUserService /
        // IShellCallback) is generated from .aidl, and the Kotlin sources reference the generated
        // Stub/Proxy classes, so without this the app will not compile.
        aidl = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/versions/9/previous-compilation-data.bin",
            "/META-INF/INDEX.LIST",
            "/META-INF/DEPENDENCIES",
            "/META-INF/io.netty.versions.properties",
            // Several JVM libraries (BouncyCastle, sshj, smbj) each ship their own copy of these.
            "/META-INF/LICENSE",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE.txt",
            "/META-INF/LICENSE-notice.md",
            "/META-INF/NOTICE",
            "/META-INF/NOTICE.md",
            "/META-INF/NOTICE.txt",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
        )
        jniLibs {
            // Extract native libs to disk: bundled CLI tools must exist as real executable files in
            // nativeLibraryDir, which is impossible when libs are loaded straight from the APK.
            useLegacyPackaging = true
            // Do NOT run llvm-strip on the staged CLI binaries. rclone is a static Go binary that
            // AGP's default native-lib stripping corrupts (it is not a conventional .so); busybox
            // and the zstd CLI are likewise standalone executables, not link targets. dftbtool is
            // tiny and built unstripped on purpose for its on-device smoke test.
            keepDebugSymbols += setOf(
                "**/librclone.so",
                "**/libbusybox.so",
                "**/libzstd_bin.so",
                "**/libnsenter.so",
                "**/libdftbtool.so",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = false
        disable += setOf("OldTargetApi", "MissingTranslation")
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

dependencies {
    // Kotlin
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Elevated execution (Tier 2 / Tier 3)
    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.libsu.nio)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Archiving & transfer engines (Tier 1 pure-JVM path)
    implementation(libs.commons.compress)
    implementation(libs.commons.io)
    implementation(libs.commons.net)
    implementation(libs.xz)
    // zstd: Android natives come from the @aar; JVM unit tests use the plain jar (host natives).
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    testImplementation(libs.zstd.jni)
    implementation(libs.sshj)
    implementation(libs.smbj)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
