import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release 키가 없으면 unsigned APK를 만들며 Debug 키로 대체하지 않는다.
val releaseSigningInputs = listOf(
    "HARUGUARD_KEYSTORE_FILE", "HARUGUARD_KEY_ALIAS", "HARUGUARD_STORE_PASSWORD", "HARUGUARD_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val releaseSigningReady = releaseSigningInputs.values.all { !it.isNullOrBlank() }
check(releaseSigningReady || releaseSigningInputs.values.all { it.isNullOrBlank() }) {
    "Release signing requires all HARUGUARD_KEYSTORE_FILE, HARUGUARD_KEY_ALIAS, HARUGUARD_STORE_PASSWORD and HARUGUARD_KEY_PASSWORD inputs."
}

// 화면 표시와 APK 업데이트 판정에 같은 버전 정보를 사용한다.
// https://developer.android.com/studio/publish/versioning
val appVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val appVersionName = providers.gradleProperty("haruguardVersionName")
    .getOrElse(appVersion.getProperty("versionName"))
val appVersionCode = providers.gradleProperty("haruguardVersionCode")
    .getOrElse(appVersion.getProperty("versionCode")).toInt()
check(appVersionName.matches(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))) {
    "versionName must use major.minor.patch format (for example, 0.0.1)."
}
check(appVersionCode in 1..2_100_000_000) { "versionCode must be between 1 and 2100000000." }

android {
    namespace = "com.hanter.haruguard"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hanter.haruguard"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

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
        // Android 10에서 최적화된 Kotlin 클래스의 재배치/접근 오류가 발생해
        // Debug와 Release 모두 R8 최적화를 사용하지 않는다.
        // https://developer.android.com/topic/performance/app-optimization/optimize-specified-packages
        debug {
            optimization {
                enable = false
            }
        }
        release {
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("fixedRelease")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
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
