plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.partychat.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.partychat.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.5"
    }

    // A fixed personal signing key, so builds from your PC and from GitHub
    // install over each other without uninstalling first.
    signingConfigs {
        create("personal") {
            storeFile = file("partychat.keystore")
            storePassword = "partychat"
            keyAlias = "partychat"
            keyPassword = "partychat"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("personal")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
