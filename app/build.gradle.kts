plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.himawari.vumeter"
    compileSdk = 36

    defaultConfig {
        applicationId = "jp.himawari.vumeter"
        minSdk = 31
        targetSdk = 36
        versionCode = 24
        versionName = "1.23"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
}
