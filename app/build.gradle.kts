plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.syed.tvpilot"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.syed.tvpilot"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "0.5.4"
        // Phones only: keeps the on-device text recogniser's native code small.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
        // Optional defaults baked in at build time, so the app works out of the box.
        val d1Url = (project.findProperty("d1Url") as String?) ?: System.getenv("D1_URL") ?: ""
        val d1Key = (project.findProperty("d1Key") as String?) ?: System.getenv("D1_KEY") ?: ""
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$d1Url\"")
        buildConfigField("String", "DEFAULT_API_KEY", "\"$d1Key\"")
        val asrUrl = (project.findProperty("asrUrl") as String?) ?: System.getenv("ASR_URL") ?: ""
        buildConfigField("String", "DEFAULT_ASR_URL", "\"$asrUrl\"")
    }

    // One fixed key for every build, so new versions install over old ones.
    val stableKeystore = (project.findProperty("tvpilotKeystore") as String?)?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (stableKeystore != null) create("stable") {
            storeFile = stableKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK installs straight away.
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // ADB over Wi-Fi, pure Kotlin
    implementation("dev.mobile:dadb:2.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    // On-device text recognition for reading titles off TV screenshots
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
