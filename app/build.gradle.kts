plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.keyboard"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal-use convenience: sign the release build with the debug key so it installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Emoji picker (Google's AndroidX library): categories, skin tones, recents, current Unicode emoji.
    implementation("androidx.emoji2:emoji2-emojipicker:1.5.0")
    // The picker is styled with AppCompat theme attributes; its theme is applied only to the emoji panel.
    implementation("androidx.appcompat:appcompat:1.7.0")
}
