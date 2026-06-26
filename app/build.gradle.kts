/**
 * app/build.gradle.kts: Android application module configuration.
 * Unified build configuration for Kotlin host + C++ JNI backend.
 *
 * Responsibilities:
 * 1. Android build configuration (SDK, minSdk, compileSdk, ndkVersion)
 * 2. Kotlin compilation flags (JVM target, kapt processors for Room)
 * 3. CMake integration for llama.cpp + JNI compilation
 * 4. All Kotlin/Android dependencies
 * 5. JNI ABIs configuration (arm64-v8a, x86_64 for emulator testing)
 *
 * Target: 6GB RAM Snapdragon 6 Gen 1 (low-end device optimization)
 */

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.android.exe"
    compileSdk = rootProject.extra["compileSdk"] as Int
    ndkVersion = rootProject.extra["ndkVersion"] as String

    defaultConfig {
        applicationId = "com.android.exe"
        minSdk = rootProject.extra["minSdk"] as Int
        targetSdk = rootProject.extra["targetSdk"] as Int
        versionCode = (System.currentTimeMillis() / 60000).toInt()
        versionName = "0.1.${(System.currentTimeMillis() / 60000).toInt()}"

        // Test instrumentation
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // JNI ABIs for llama.cpp
        ndk {
            abiFilters += listOf(
                "arm64-v8a",  // Primary: Snapdragon 6 Gen 1
                "x86_64"      // Secondary: emulator support
            )
        }

        // CMake build options
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                cFlags += "-fPIC"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",            // Disable OpenMP on low-end
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_SHARED=ON",            // Build as shared library
                    "-DCMAKE_BUILD_TYPE=Release"    // Release mode optimization
                )
            }
        }

        // Room database schema location for migrations
        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf(
                    "room.schemaLocation" to "${projectDir}/schemas"
                )
            }
        }
    }

    // CMake integration for llama.cpp + JNI compilation
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = rootProject.extra["cmakeVersion"] as String
        }
    }

    // Build types
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
    }

    // Kotlin compilation options
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xopt-in=kotlin.ExperimentalCoroutinesApi",
            "-Xopt-in=kotlin.RequiresOptIn"
        )
    }

    // View and resource binding
    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = false
    }

    // Packaging configuration
    packaging {
        resources {
            excludes += listOf(
                "/META-INF/AL2.0",
                "/META-INF/LGPL2.1",
                "/META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    // ===============================================
    // Android Core
    // ===============================================
    implementation("androidx.core:core-ktx:${rootProject.extra["coreKtxVersion"]}")
    implementation("androidx.appcompat:appcompat:${rootProject.extra["appCompatVersion"]}")
    implementation("com.google.android.material:material:${rootProject.extra["materialVersion"]}")
    implementation("androidx.constraintlayout:constraintlayout:${rootProject.extra["constraintLayoutVersion"]}")
    implementation("androidx.activity:activity-ktx:${rootProject.extra["activityKtxVersion"]}")
    implementation("androidx.fragment:fragment-ktx:${rootProject.extra["fragmentKtxVersion"]}")

    // ===============================================
    // Lifecycle & Services
    // ===============================================
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:${rootProject.extra["lifecycleVersion"]}")
    implementation("androidx.lifecycle:lifecycle-service:${rootProject.extra["lifecycleServiceVersion"]}")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${rootProject.extra["lifecycleVersion"]}")

    // ===============================================
    // Room Database (for soul persistence)
    // ===============================================
    val roomVersion = rootProject.extra["roomVersion"] as String
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // ===============================================
    // Coroutines (async/threading)
    // ===============================================
    val coroutinesVersion = rootProject.extra["coroutinesVersion"] as String
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // ===============================================
    // Serialization & JSON
    // ===============================================
    implementation("com.google.code.gson:gson:${rootProject.extra["gsonVersion"]}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${rootProject.extra["serializationVersion"]}")

    // ===============================================
    // 3D Rendering (Executor: avatar rendering)
    // ===============================================
    val filamentVersion = rootProject.extra["filamentVersion"] as String
    implementation("com.google.android.filament:filament-android:$filamentVersion")
    implementation("com.google.android.filament:gltfio-android:$filamentVersion")
    implementation("com.google.android.filament:image-android:$filamentVersion")

    // ===============================================
    // File Handling
    // ===============================================
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ===============================================
    // Logging
    // ===============================================
    implementation("com.jakewharton.timber:timber:${rootProject.extra["timberVersion"]}")

    // ===============================================
    // Testing
    // ===============================================
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
