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
        versionCode = 25
        versionName = "0.6.16"
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

// ======================================================================
// Healer build support (audits H-4, H-5)
// ======================================================================

/**
 * H-5: sync the bundled source mirror (assets/source) from the live tree
 * on every build, so the healer's LLM always reads the code that matches
 * the running binary. The audited repo shipped a mirror that had drifted
 * from the real sources — patches would be generated against stale code.
 * Wired into preBuild: drift is structurally impossible.
 */
tasks.register<Sync>("syncSourceMirror") {
    from("src/main/java")
    into("src/main/assets/source")
}

tasks.named("preBuild") { dependsOn("syncSourceMirror") }

val healerAssetsDir = layout.buildDirectory.dir("generated/healer/assets")

/**
 * H-4: package the app's compiled classes as the healer's compile
 * classpath (assets/healer/app_classes.jar). The audited code referenced a
 * path that was never populated — every Termux compile was doomed. The
 * healer extracts this jar once and stages it into the workspace so
 * kotlinc can resolve the app's own types (Tool, ToolContext, ...).
 *
 * The output DIRECTORY is the generated assets root, and the nested
 * archive name ("healer/app_classes.jar") places the jar one level below
 * it — the APK still ships assets/healer/app_classes.jar, the exact path
 * HealingAgent reads. The root-level output directory is required by the
 * addGeneratedSourceDirectory wiring below.
 */
tasks.register<Jar>("packageHealerClasspath") {
    archiveFileName.set("healer/app_classes.jar")
    destinationDirectory.set(healerAssetsDir)
    // The compiled app classes (Kotlin output + generated BuildConfig) so a
    // patch can compile against every app type it references.
    from(layout.buildDirectory.dir("tmp/kotlin-classes/debug"))
    from(layout.buildDirectory.dir("generated/source/buildConfig/debug"))
    dependsOn("compileDebugKotlin")
}

androidComponents {
    onVariants { variant ->
        // Register the jar task as a GENERATED asset producer through the
        // variant API. AGP then threads a provider-based dependency into
        // EVERY consumer of the variant's assets — merge<Variant>Assets,
        // the lint model writer, lintVital — for every variant. The
        // previous manual `assets.srcDir(...)` plus a debug-only
        // dependsOn wiring left the release consumers with an implicit
        // dependency on the producer, which Gradle 8 reports as a
        // WorkValidationException whenever parallel ordering ran the
        // producer first: CI run #57 died exactly that way while identical
        // code passed three other runs. A build-ordering coin flip, now
        // structurally impossible.
        variant.sources.assets?.addGeneratedSourceDirectory(
            tasks.named("packageHealerClasspath", Jar::class.java),
            // Lambda (not Jar::destinationDirectory — a synthetic Java
            // property reference, which needs Kotlin 2.1).
            { it.destinationDirectory },
        )
    }
}

// Belt and suspenders: the generated-asset registration above redirects the
// task's output to AGP's conventional directory, but in this AGP version it
// does NOT reliably attach the dependency edge to every consumer task (CI
// run #57 died on mergeReleaseAssets; the local build surfaced
// generateReleaseLintVitalReportModel). Declare the ordering explicitly for
// the concrete consumer task types as well — a duplicate dependsOn is
// idempotent, so both mechanisms can coexist, and the build order becomes
// deterministic instead of a task-scheduling coin flip.
tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
    dependsOn("packageHealerClasspath")
}
tasks.withType<com.android.build.gradle.internal.lint.LintModelWriterTask>().configureEach {
    dependsOn("packageHealerClasspath")
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
