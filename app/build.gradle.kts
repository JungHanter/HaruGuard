plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// No debug-key fallback for release. Without these inputs, assembleRelease produces an unsigned APK.
val releaseSigningInputs = listOf(
    "HARUGUARD_KEYSTORE_FILE", "HARUGUARD_KEY_ALIAS", "HARUGUARD_STORE_PASSWORD", "HARUGUARD_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val releaseSigningReady = releaseSigningInputs.values.all { !it.isNullOrBlank() }
check(releaseSigningReady || releaseSigningInputs.values.all { it.isNullOrBlank() }) {
    "Release signing requires all HARUGUARD_KEYSTORE_FILE, HARUGUARD_KEY_ALIAS, HARUGUARD_STORE_PASSWORD and HARUGUARD_KEY_PASSWORD inputs."
}

android {
    namespace = "com.hanter.haruguard"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hanter.haruguard"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("fixedRelease") {
                storeFile = file(requireNotNull(releaseSigningInputs["HARUGUARD_KEYSTORE_FILE"]))
                keyAlias = releaseSigningInputs["HARUGUARD_KEY_ALIAS"]
                storePassword = releaseSigningInputs["HARUGUARD_STORE_PASSWORD"]
                keyPassword = releaseSigningInputs["HARUGUARD_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("fixedRelease")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
