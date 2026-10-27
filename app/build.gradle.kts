plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Release signing is optional. Values are read from Gradle properties first, then environment
// variables, so a keystore can be supplied by CI or a local shell without committing secrets.
// When any value is missing, the release variant is built unsigned.
val releaseSigningValues = listOf(
    "KEYSTORE_FILE",
    "KEYSTORE_PASSWORD",
    "KEY_ALIAS",
    "KEY_PASSWORD",
).associateWith { name ->
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
}

val hasReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }

android {
    namespace = "com.dinoe.clockwise"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dinoe.clockwise"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0-beta.1"
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
