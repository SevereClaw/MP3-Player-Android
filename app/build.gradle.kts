plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "com.severeclaw.mp3player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.severeclaw.mp3player"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "сборка $buildNumber"
    }

    signingConfigs {
        create("release") {
            // Один и тот же ключ для всех сборок — иначе Android не даст поставить обновление поверх.
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "zsauygzTa8cOG4lLS3Itx4E3"
            keyAlias = "mp3player"
            keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: "zsauygzTa8cOG4lLS3Itx4E3"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
