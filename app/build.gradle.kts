plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jarvis.kiosk"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.jarvis.kiosk"
        minSdk = 21
        targetSdk = 34
        versionCode = (project.findProperty("VERSION_CODE") as? String)?.toInt() ?: 1
        versionName = (project.findProperty("VERSION_NAME") as? String) ?: "1.0.0"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.10.0")

    // SDK Impressora Sunmi (nativa D2 Mini / T2 / V2)
    implementation("com.sunmi:printerlibrary:1.0.24")

    // JSON parsing para payloads de impressao via REST
    implementation("com.google.code.gson:gson:2.10.1")
}