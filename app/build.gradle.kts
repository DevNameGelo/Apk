plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One fixed signing key for every build, so a new APK installs over the old one and keeps playlists/favorites.
// A different key can be supplied through the MR_* environment variables (GitHub Actions secrets).
fun signingValue(name: String, fallback: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "com.musicrage.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.musicrage.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 13
        versionName = "1.2.8"
    }

    signingConfigs {
        create("rage") {
            storeFile = rootProject.file("signing/music-rage.keystore")
            storePassword = signingValue("MR_KEYSTORE_PASSWORD", "VfJXFda1DIH8bpmLLsijkPP9")
            keyAlias = signingValue("MR_KEY_ALIAS", "musicrage")
            keyPassword = signingValue("MR_KEY_PASSWORD", "VfJXFda1DIH8bpmLLsijkPP9")
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("rage")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rage")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
