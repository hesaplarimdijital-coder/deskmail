plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself (built-in Kotlin). Declaring the Kotlin Gradle plugin here, never
    // applied, puts the catalog's Kotlin on the build classpath, so AGP, the Compose compiler plugin
    // and the pure Kotlin/JVM module all use that version (as in desktop-kit).
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
