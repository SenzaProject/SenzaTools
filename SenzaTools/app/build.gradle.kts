plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Nomor run GitHub Actions. Dipakai sebagai nomor build: tiap build CI lebih besar dari sebelumnya,
// dan aplikasi membandingkannya dengan run sukses terbaru untuk meminta pembaruan. 0 = build lokal.
val ciRunNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "com.senzaproject.senzatools"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.senzaproject.senzatools"
        minSdk = 31          // Android 12
        targetSdk = 35
        versionCode = if (ciRunNumber > 0) ciRunNumber else 1
        versionName = "1.2.0"

        buildConfigField("int", "BUILD_NUMBER", "$ciRunNumber")
        // Sumber update: run sukses terbaru workflow "Build APK" di branch main (API publik GitHub).
        buildConfigField(
            "String", "UPDATE_API",
            "\"https://api.github.com/repos/SenzaProject/SenzaTools/actions/workflows/build-apk.yml/runs?branch=main&status=success&per_page=1\""
        )
        buildConfigField("String", "UPDATE_PAGE", "\"https://github.com/SenzaProject/SenzaTools/actions\"")
    }

    // Tanda tangan tetap. Runner GitHub membuat debug key baru di tiap build, sehingga APK dari run berbeda
    // tidak bisa dipasang di atas satu sama lain ("aplikasi tidak terpasang"). Keystore ini ikut di repo;
    // untuk distribusi publik, timpa lewat environment SENZA_KEYSTORE / SENZA_STORE_PASSWORD / SENZA_KEY_ALIAS / SENZA_KEY_PASSWORD.
    signingConfigs {
        create("senza") {
            storeFile = file(System.getenv("SENZA_KEYSTORE") ?: "senza-release.jks")
            storePassword = System.getenv("SENZA_STORE_PASSWORD") ?: "senzatools"
            keyAlias = System.getenv("SENZA_KEY_ALIAS") ?: "senzatools"
            keyPassword = System.getenv("SENZA_KEY_PASSWORD") ?: "senzatools"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("senza")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("senza")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
