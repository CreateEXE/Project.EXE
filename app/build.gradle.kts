import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.kapt)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
    else logger.warn("local.properties not found — run setup_projectexe.sh to create it.")
}

android {
    namespace  = "com.projectexe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.projectexe"
        minSdk        = 29
        targetSdk     = 36
        versionCode   = 2
        versionName   = "2.0.0-alpha"

        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable        = true
            isMinifyEnabled     = false
            applicationIdSuffix = ".debug"
            buildConfigField("String",  "OPENROUTER_API_KEY",     "\"${localProps.getProperty("OPENROUTER_API_KEY",  "")}\"")
            buildConfigField("String",  "OPENROUTER_MODEL",       "\"${localProps.getProperty("OPENROUTER_MODEL",    "meta-llama/llama-3.1-8b-instruct:free")}\"")
            buildConfigField("boolean", "ENABLE_AUDITOR_LOGGING",  "true")
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String",  "OPENROUTER_API_KEY",     "\"${localProps.getProperty("OPENROUTER_API_KEY",  "")}\"")
            buildConfigField("String",  "OPENROUTER_MODEL",       "\"${localProps.getProperty("OPENROUTER_MODEL",    "meta-llama/llama-3.1-8b-instruct:free")}\"")
            buildConfigField("boolean", "ENABLE_AUDITOR_LOGGING",  "false")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.okhttp.sse)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
