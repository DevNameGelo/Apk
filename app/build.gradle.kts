plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One fixed signing key for every build, so a new APK installs over the old one and keeps playlists/favorites.
// A different key can be supplied through the MR_* environment variables (GitHub Actions secrets).
fun signingValue(name: String, fallback: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

// Versioning: the versionName is derived from the versionCode, so the numbers can never disagree.
// Patch counts up to 1000 before the minor rolls over (1.2.31 … 1.2.1000 -> 1.3.0), and the minor
// counts up to 10 before the major rolls over (1.9.1000 -> 1.10.0 -> 2.0.0 when the 10th minor is full).
// 1.2.24 was code 29, so 30 is 1.2.30 (the brief "1.3.0" of code 30 is folded back into the scheme).
fun versionNameFromCode(code: Int): String {
    // code 1 = v1.0.0: each code is one patch step, minors hold 1000 patches, majors hold 10 minors.
    val totalPatches = code - 1
    val major = 1 + totalPatches / 10_000
    val minor = (totalPatches % 10_000) / 1_000
    val patch = totalPatches % 1_000
    return "$major.$minor.$patch"
}

android {
    namespace = "com.musicrage.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.musicrage.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 31
        versionName = versionNameFromCode(31)
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
