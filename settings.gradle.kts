/**
 * settings.gradle.kts: Module configuration and repository setup.
 * Defines the project structure: root, :app module, and future :core:* library modules.
 */

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Zeroclaw"
include(":app")

// Future module includes for Phase 3+ (core libraries)
// include(":core:soul")
// include(":core:inference")
// include(":core:memory")
// include(":core:entity")
// include(":features:avatar")
// include(":features:accessibility")
// include(":features:overlay")
