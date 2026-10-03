plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val debugKeystore = rootProject.file("debug.keystore")

android {
    namespace = "com.litewidget.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.litewidget.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "0.3.0"
    }

    signingConfigs {
        getByName("debug") {
            if (debugKeystore.exists()) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
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
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/*.version")
    }
}
