import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kaliscriber.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kaliscriber.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.3.5"

    }

    // Release signing comes from the environment (CI secret or a local keystore), so the key never
    // lives in the repository. Without it, release builds fall back to the debug key.
    val releaseKeystore = System.getenv("KALISCRIBER_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("kaliscriber") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KALISCRIBER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KALISCRIBER_KEY_ALIAS") ?: "kaliscriber"
                keyPassword = System.getenv("KALISCRIBER_KEY_PASSWORD") ?: System.getenv("KALISCRIBER_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("kaliscriber") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Compress the speech engine's native libraries: about half the download size.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // One small APK per CPU type (arm64 suits almost every current phone) plus a universal one.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// On-device speech recognition and speaker diarization (https://github.com/k2-fsa/sherpa-onnx).
// The 50 MB AAR is fetched from the official release once and verified, rather than committed.
val sherpaOnnxVersion = "1.13.8"
val sherpaOnnxSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"
val sherpaOnnxAar = file("libs/sherpa-onnx-$sherpaOnnxVersion.aar")

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}

if (!sherpaOnnxAar.exists()) {
    sherpaOnnxAar.parentFile.mkdirs()
    val part = File(sherpaOnnxAar.parentFile, sherpaOnnxAar.name + ".part")
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaOnnxVersion/sherpa-onnx-$sherpaOnnxVersion.aar"
    logger.lifecycle("Downloading $url")
    URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
    val actual = sha256(part)
    check(actual == sherpaOnnxSha256) {
        part.delete()
        "sherpa-onnx AAR checksum mismatch: expected $sherpaOnnxSha256 but was $actual"
    }
    check(part.renameTo(sherpaOnnxAar)) { "Could not move ${part.name} into place" }
}

dependencies {
    implementation(project(":core"))
    implementation(files(sherpaOnnxAar))
    implementation(libs.commons.compress)
    implementation(libs.play.services.auth)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
