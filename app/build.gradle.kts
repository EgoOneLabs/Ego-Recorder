plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.egouvc.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.egouvc.phone"
        minSdk = 31
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.3-raw-mjpeg"
    }

    buildFeatures {
        viewBinding = true
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
    implementation(project(":uvccamera"))
    testImplementation("junit:junit:4.13.2")
}
