plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// One fixed signing key for every build, so a new APK installs over the old one and keeps playlists/favorites.
// A different key can be supplied through the MR_* environment variables (GitHub Actions secrets).
fun signingValue(name: String, fallback: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

// Versioning: the versionName is derived from the versionCode, so the numbers can never disagree.
// The scheme: a patch counts up to 1000 before the minor rolls over (1.2.31 … 1.2.999 -> 1.3.0),
// and the minor counts up to 10 before the major rolls over (… 1.9.999 -> 1.10.0 … 1.10.999 -> 2.0.0).
// 1.2.24 was code 29, so code 31 is 1.2.31 ("1.3.0" on code 30 is folded back into the scheme).
fun versionNameFromCode(code: Int): String {
    // Counter c=31 -> 1.2.31, i.e. 31 patch steps into minor 2; each minor holds 1000 steps and
    // each major holds 11 minors (0..10 — "1.10.x" is a real minor), so:
    val steps = code + 2000
    val major = 1 + steps / 11_000
    val minor = (steps % 11_000) / 1_000
    val patch = steps % 1_000
    return "$major.$minor.$patch"
}

android {
    namespace = "com.musicrage.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.musicrage.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 32
        versionName = versionNameFromCode(32)
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
