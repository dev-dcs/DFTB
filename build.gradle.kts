plugins {
    alias(libs.plugins.android.application) apply false
    // Pinned here (apply false) so one Kotlin version sits on the buildscript classpath for every
    // module. AGP 9 has built-in Kotlin support: :app must NOT apply kotlin-android itself.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
