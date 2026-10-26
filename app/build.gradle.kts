plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shuyu.voiceime"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shuyu.voiceime"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // A fixed key so that new builds install over old ones. Override with env vars for your own key.
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "voiceime.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "voiceime123"
            keyAlias = System.getenv("KEY_ALIAS") ?: "voiceime"
            keyPassword = System.getenv("KEY_PASSWORD") ?: "voiceime123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("txt") }
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    // Handwriting (model downloaded on first use)
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    // sherpa-onnx (offline ASR: SenseVoice / Whisper), repackaged on Maven Central
    implementation("com.bihe0832.android:lib-sherpa-onnx:8.6.10") { isTransitive = false }
    implementation("com.bihe0832.android:lib-onnx:8.5.4") { isTransitive = false }
}
