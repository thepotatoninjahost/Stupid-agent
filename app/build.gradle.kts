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
        versionCode = 12
        versionName = "0.6.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        ndk {
            // GenieX AAR only ships arm64-v8a libs (Hexagon NPU is ARM-only).
            // x86_64 stripped to halve APK size on emulators / no-NPU devices.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Release signing is driven by environment variables so that no key
        // material ever lives in the repository. CI decodes a base64 keystore
        // from GitHub Actions secrets. Local builds without the env vars simply
        // produce an unsigned release APK (assembleRelease still works).
        create("release") {
            val ksPath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
        // NOTE: the debug build intentionally uses the standard Android debug
        // keystore (~/.android/debug.keystore, auto-generated). The previously
        // committed vorv-debug.keystore was removed from the repository — see
        // CHANGES.md (audit finding H-7 / supply-chain hygiene).
    }

    buildTypes {
        debug {
            // Nothing custom — standard debug keystore, debuggable as expected.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only sign when the keystore env vars are present (CI release job).
            if (System.getenv("RELEASE_KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric needs resources; strict mode keeps tests honest.
            isIncludeAndroidResources = true
        }
    }
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
        // The agent's vorv_runner.py uses only stdlib (sys, io, time, threading).
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
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.rhino)
    implementation(libs.geniex.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.jsoup)
    implementation(libs.gson)
    debugImplementation(libs.androidx.ui.tooling)

    // Test-only
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.room.testing)
}
