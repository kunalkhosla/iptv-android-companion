plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Local convenience values for debug builds: server URL + auto-login
// creds. Source them from gradle properties (per-user
// ~/.gradle/gradle.properties is the standard Android spot) or env
// vars (CI sets these from GitHub Actions secrets). Empty fallback
// keeps the login screen behavior for anyone who builds without
// configuring either — no surprise creds in checked-in source.
fun secretOrEmpty(key: String): String =
    (project.findProperty(key) as String?) ?: System.getenv(key) ?: ""

android {
    namespace = "com.khouch.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.khouch.tv"
        // minSdk 26 lets us ship adaptive icons (mipmap-anydpi-v26)
        // without legacy mipmap-hdpi PNG fallbacks. Every Android TV
        // still in service in 2026 runs Android 8 (API 26) or newer.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Shared debug keystore — see app-phone/build.gradle.kts for why.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
            // Server URL + auto-login creds for the family TV install
            // come from secretOrEmpty() at top of file. Empty values
            // fall through to the normal login flow. See
            // LoginViewModel.tryAutoLogin().
            buildConfigField("String", "DEFAULT_SERVER_URL",
                "\"${secretOrEmpty("DEFAULT_SERVER_URL")}\"")
            buildConfigField("String", "AUTO_LOGIN_USER",
                "\"${secretOrEmpty("AUTO_LOGIN_USER")}\"")
            buildConfigField("String", "AUTO_LOGIN_PASS",
                "\"${secretOrEmpty("AUTO_LOGIN_PASS")}\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")
            buildConfigField("String", "AUTO_LOGIN_USER", "\"\"")
            buildConfigField("String", "AUTO_LOGIN_PASS", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
        )
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

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    // Compose BOM — keep this aligned with the Compose for TV libs below.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Core + lifecycle + activity
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose foundation + material (used for non-TV-specific primitives)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-text-google-fonts:1.7.2")
    implementation("androidx.compose.material:material-icons-extended")
    // material3 — needed for CircularProgressIndicator (the
    // tv-material3 catalog doesn't ship a progress indicator).
    implementation("androidx.compose.material3:material3")

    // Coil SVG decoder — only the :app module references
    // SvgDecoder.Factory directly (KhouchApp registers it on the
    // singleton ImageLoader). Android-specific artifact because
    // the KMP coil-svg metadata module doesn't ship JVM classes.
    implementation("io.coil-kt.coil3:coil-svg-android:3.0.0-rc01")

    // Compose for TV
    implementation("androidx.tv:tv-foundation:1.0.0-alpha11")
    implementation("androidx.tv:tv-material:1.0.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // Media3 (ExoPlayer)
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-session:$media3")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Images
    implementation("io.coil-kt.coil3:coil-compose:3.0.0-rc01")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.0-rc01")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // DI
    val koin = "4.0.0"
    implementation("io.insert-koin:koin-android:$koin")
    implementation("io.insert-koin:koin-androidx-compose:$koin")

    // Debug only
    debugImplementation("androidx.compose.ui:ui-tooling")
}
