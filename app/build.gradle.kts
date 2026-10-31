plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "host.vorv"
    compileSdk = 35

    defaultConfig {
        applicationId = "host.vorv"
        minSdk = 31
        targetSdk = 35
        versionCode = 9
        versionName = "0.5.1-vorv"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk {
            // GenieX AAR only ships arm64-v8a libs (Hexagon NPU is ARM-only).
            // x86_64 stripped to halve APK size on emulators / no-NPU devices.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../vorv-debug.keystore")
            storePassword = "android"
            keyAlias = "vorvdebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // Required by GenieX — extracts .so files to local filesystem at install
        // time so the model manager can find them at runtime. Verified pattern
        // from Qualcomm's official geniex_chat_android sample (build.gradle line 41).
        jniLibs { useLegacyPackaging = true }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        // Python 3.11 stdlib only — no external packages.
        // Chaquopy doesn't ship a C compiler, so anything with native deps fails.
        // The agent's vorv_runner.py uses only stdlib (sys, io, signal).
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.rhino)
    implementation(libs.geniex.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose)
    debugImplementation(libs.androidx.ui.tooling)
}
