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
     versionCode = providers.gradleProperty("VERSION_CODE")
    .orNull?.toInt() ?: 1

versionName = providers.gradleProperty("VERSION_NAME")
    .orNull ?: "1.0"
    }

   signingConfigs {
    create("release") {
        storeFile = rootProject.file("debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
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
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Emoji picker (Google's AndroidX library): categories, skin tones, recents, current Unicode emoji.
    implementation("androidx.emoji2:emoji2-emojipicker:1.5.0")
    // The picker is styled with AppCompat theme attributes; its theme is applied only to the emoji panel.
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
