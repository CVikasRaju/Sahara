plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itantra"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itantra"
        minSdk = 24        // Android 7.0 — chosen to cover low/mid-range devices in the field,
                            // per the problem statement's hardware constraint. Raise only if a
                            // required BLE/WiFi-Direct API genuinely forces it.
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-scaffold"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Prevents Gradle from compressing .onnx and .bin models so Sherpa-ONNX can mmap them directly
    androidResources {
        noCompress += listOf("onnx", "bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Sherpa-ONNX: offline neural inference (VAD, STT, TTS) via ONNX Runtime Mobile.
    // See docs/ML_PIPELINE.md for model selection and compression pipeline details.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:1.13.6")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
