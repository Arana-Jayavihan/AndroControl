plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.aranaj.androcontrol"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aranaj.androcontrol"
        minSdk = 23  // Requires Android 6.0+ for secure storage (AES-GCM via Android Keystore)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 16 KB page-size compliance: keep native libraries uncompressed and
    // page-aligned (AGP 8.5.1+ aligns to 16 KB automatically).
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains:annotations:23.0.0")
    implementation("com.google.code.gson:gson:2.8.9")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // Security - for encrypted storage (optional, uses Android Keystore directly)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // QR Code scanning - Google ML Kit + CameraX
    // Versions below ship 16 KB-page-aligned native libraries.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.guava:guava:31.1-android")
}