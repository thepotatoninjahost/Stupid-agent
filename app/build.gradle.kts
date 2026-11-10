import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.ListProperty

abstract class HealerClasspathJar : Jar() {
    @get:InputFiles
    abstract val classJars: ListProperty<RegularFile>
    @get:InputFiles
    abstract val classDirectories: ListProperty<Directory>

    init {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        include("**/*.class", "**/*.kotlin_builtins", "META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*")
        from(classDirectories)
        from(classJars.map { jars -> jars.map { project.zipTree(it.asFile) } })
        eachFile {
            if (path.startsWith("META-INF/LICENSE") || path.startsWith("META-INF/NOTICE")) {
                val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                path = "META-INF/healer-notices/$digest/$name"
            }
        }
        exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/MANIFEST.MF")
    }
}

abstract class HealerApiAssets : DefaultTask() {
    @get:InputFile
    abstract val apiJar: RegularFileProperty
    @get:InputFile
    abstract val apiNotice: RegularFileProperty
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
    @TaskAction
    fun packageApi() {
        val target = outputDirectory.file("healer/android_api.jar").get().asFile
        target.parentFile.mkdirs()
        ZipFile(apiJar.get().asFile).use { source ->
            ZipOutputStream(target.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("META-INF/NOTICE.txt").apply { time = 0L })
                apiNotice.get().asFile.inputStream().use { it.copyTo(output) }
                output.closeEntry()
                source.entries().asSequence().filter {
                    !it.isDirectory && (it.name.endsWith(".class") ||
                        it.name.contains("NOTICE") || it.name.contains("LICENSE"))
                }.forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                    source.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    // Dependency wave 2026-11-09 / AGP 9: Kotlin support is BUILT IN to AGP
    // 9.0+ — the kotlin-android plugin is removed (applying it alongside
    // AGP 9 is a hard error, not a warning). The Kotlin toolchain version
    // is resolved from the KGP-based plugins still on the classpath
    // (kotlin-compose below), so the catalog's kotlin pin stays meaningful.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "host.vorv"
    // Dependency wave 2026-11-09: compileSdk 35 → 37 (Android 17) — the floor
    // demanded by navigation-compose 2.10.2 and the Compose 1.12 BOM
    // (compileSdk 37, AGP 9.1+ per their AAR metadata). targetSdk stays 35
    // this wave deliberately: a target bump changes RUNTIME behaviors
    // (notification trampolines, FGS rules, broadcast delivery) and would
    // contaminate the owner's first on-device acceptance pass of the v0.7.6
    // remediation. It rides the NEXT wave, on its own, with its own tests.
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "host.vorv"
        minSdk = 31
        targetSdk = 35
        versionCode = 38
        versionName = "0.8.1"
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
        // v0.7.0 field finding #9 — the debug key is now COMMITTED on purpose.
        //
        // History: audit H-7 removed a previously committed keystore as
        // supply-chain hygiene. That was right for RELEASE keys. But the
        // default debug key is machine-local (~/.android/debug.keystore,
        // auto-generated), and Vorv is sideload-only — every APK must install
        // over the previous one. When the build machine dies (it did: the
        // v0.6.x sandbox was recycled on 2026-11-05), the new machine's key
        // mismatches the installed app, Android refuses the update, and a
        // phone-only user must uninstall — losing every memory, task log and
        // setting — just to accept an update. A committed DEBUG key makes every build — CI, sandbox, fork —
        // signature-compatible with the installed base, at the cost of
        // making its update-signing identity publicly reproducible.
        // The public key preserves compatibility with existing test installs,
        // but anyone with it can sign a replacement APK for that identity.
        // A private production key needs an owner-approved migration plan;
        // changing this key silently would prevent updates to installed apps.
        // (getByName, not create — AGP pre-creates the "debug" config; we
        // override its key material with the committed keystore.)
        getByName("debug") {
            storeFile = file("vorv-debug.keystore")
            storePassword = "android"
            keyAlias = "vorvdebug"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // v0.7.0: pinned to the committed debug keystore (see
            // signingConfigs above) — stable signature across machines.
            signingConfig = signingConfigs.getByName("debug")
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
    // AGP 9 built-in Kotlin: kotlinOptions is gone with kotlin-android; the
    // JVM target follows compileOptions above (Java 17).
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
 * L-7 (code review, 2026-11): the jar is PER-VARIANT. The old single task
 * always packaged `tmp/kotlin-classes/DEBUG` and registered it as a
 * generated asset for EVERY variant — the RELEASE APK shipped
 * debug-compiled bytecode as its healer classpath, so a release-side
 * self-repair compiled patches against debug classes (different
 * BuildConfig, no R8 keep-rule alignment) while PatchVerifier compared
 * against the release originals. Each variant now embeds ITS OWN compiled
 * classes; the archive name and asset path ("healer/app_classes.jar") are
 * unchanged, so HealingAgent reads the same path in both builds.
 *
 * The output DIRECTORY is the per-variant generated assets root, and the
 * nested archive name ("healer/app_classes.jar") places the jar one level
 * below it — the APK still ships assets/healer/app_classes.jar, the exact
 * path HealingAgent reads. The root-level output directory is required by
 * the addGeneratedSourceDirectory wiring below.
 */
androidComponents {
    onVariants { variant ->
        val v = variant.name
        val capitalized = v.replaceFirstChar { it.uppercase() }
        val packageHealerClasspathVariant = tasks.register<HealerClasspathJar>("packageHealerClasspath$capitalized") {
            archiveFileName.set("healer/app_classes.jar")
            destinationDirectory.set(healerAssetsDir.map { it.dir("$v/") })
            // The compiled app classes (Kotlin output + generated BuildConfig)
            // of THIS variant, so a patch compiles against what actually runs.

        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
            .use(packageHealerClasspathVariant)
            .toGet(ScopedArtifact.CLASSES, { it.classJars }, { it.classDirectories })
        val apiAssets = tasks.register<HealerApiAssets>("packageHealerApiAssets$capitalized") {
            apiJar.set(androidComponents.sdkComponents.bootClasspath.map { files ->
                files.first { it.asFile.name == "android.jar" }
            })
            apiNotice.set(apiJar.map { jar ->
                layout.projectDirectory.file(jar.asFile.parentFile.resolve("data/NOTICE.txt").absolutePath)
            })
            outputDirectory.set(layout.buildDirectory.dir("generated/healer/api-assets/$v"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(apiAssets, { it.outputDirectory })
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
            packageHealerClasspathVariant,
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
// L-7: each consumer task is mapped to the jar of ITS OWN variant, so a
// debug build never compiles release classes or vice versa.
val knownVariants = listOf("debug", "release")
tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
    val v = knownVariants.firstOrNull {
        name.equals("merge${it.replaceFirstChar(Char::uppercase)}Assets", ignoreCase = true)
    }
    if (v != null) dependsOn("packageHealerClasspath${v.replaceFirstChar { it.uppercase() }}")
}
tasks.withType<com.android.build.gradle.internal.lint.LintModelWriterTask>().configureEach {
    val v = knownVariants.firstOrNull {
        name.contains(it.replaceFirstChar(Char::uppercase), ignoreCase = true)
    }
    if (v != null) dependsOn("packageHealerClasspath${v.replaceFirstChar { it.uppercase() }}")
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
    testImplementation(libs.androidx.work.testing)
}
