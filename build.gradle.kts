/**
 * Root build.gradle.kts: Version catalog and global plugin management.
 * Defines all dependency versions and plugin versions for the monorepo.
 *
 * Target: Android 14 (API 34), Kotlin 2.0+, 6GB RAM Snapdragon 6 Gen 1
 */

plugins {
    id("com.android.application") version "8.4.2" apply false
    id("com.android.library") version "8.4.2" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("kapt") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

// Version catalog
val compileSdkVersion = 35
val targetSdkVersion = 34
val minSdkVersion = 26
val ndkVersion = "26.1.10909125"
val cmakeVersion = "3.22.1"

extras["compileSdk"] = compileSdkVersion
extras["targetSdk"] = targetSdkVersion
extras["minSdk"] = minSdkVersion
extras["ndkVersion"] = ndkVersion
extras["cmakeVersion"] = cmakeVersion

// Dependency versions
extras["kotlinVersion"] = "2.0.21"
extras["coreKtxVersion"] = "1.13.1"
extras["appCompatVersion"] = "1.7.0"
extras["materialVersion"] = "1.12.0"
extras["constraintLayoutVersion"] = "2.1.4"
extras["lifecycleVersion"] = "2.8.3"
extras["lifecycleServiceVersion"] = "2.8.3"
extras["roomVersion"] = "2.6.1"
extras["coroutinesVersion"] = "1.8.1"
extras["gsonVersion"] = "2.11.0"
extras["activityKtxVersion"] = "1.9.0"
extras["fragmentKtxVersion"] = "1.8.1"
extras["filamentVersion"] = "1.50.2"
extras["timberVersion"] = "4.7.1"
extras["serializationVersion"] = "1.7.1"

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
