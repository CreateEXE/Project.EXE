// Root build.gradle.kts
// Gradle 9 requires all plugin versions to be resolvable from pluginManagement
// or the version catalog. We declare them here with apply false so subprojects
// can apply them without re-specifying versions.
plugins {
    alias(libs.plugins.android.application)  apply false
    alias(libs.plugins.kotlin.android)       apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp)                  apply false
}
