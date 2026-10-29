import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Signing is optional. Values are read from Gradle properties first, then environment variables,
// then an untracked keystore.properties file at the repository root, so a keystore can be supplied
// by CI or a local checkout without committing secrets. When any value is missing, the release
// variant is built unsigned.
val localSigningValues = rootProject.file("keystore.properties")
    .takeIf { it.isFile }
    ?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }

val releaseSigningValues = listOf(
    "KEYSTORE_FILE",
    "KEYSTORE_PASSWORD",
    "KEY_ALIAS",
    "KEY_PASSWORD",
).associateWith { name ->
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: localSigningValues?.getProperty(name)
}

val hasReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }

android {
    namespace = "com.dinoe.clockwise"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dinoe.clockwise"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.2.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseSigningValues.getValue("KEYSTORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("KEYSTORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Sign debug builds with the same key as release builds when one is configured, so an
            // APK built locally can update a beta APK installed from CI instead of conflicting.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // Work around a Kotlin analysis API crash in this toolchain (ExperimentalDetector):
        // disable only the two opt-in checks named by the failure. All other lint checks run.
        disable += "UnsafeOptInUsageError"
        disable += "UnsafeOptInUsageWarning"
        abortOnError = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    debugImplementation(libs.compose.ui.tooling)
}
