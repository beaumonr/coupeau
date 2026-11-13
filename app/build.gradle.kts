plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.coupeau.voicebubble"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.coupeau.voicebubble"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
    }

    // A fixed key kept in the repo so each new test build installs over the old one.
    signingConfigs {
        create("test") {
            storeFile = file("test-signing.jks")
            storePassword = "voicebubble"
            keyAlias = "voicebubble"
            keyPassword = "voicebubble"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("test") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("test")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
