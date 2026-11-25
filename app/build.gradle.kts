import java.security.MessageDigest
import java.util.zip.ZipFile

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

    defaultConfig {
        applicationId = "host.vorv"
        minSdk = 31
        targetSdk = 35
        versionCode = 83
        // The build names itself: "0.9.30+<git sha>". A screenshot of the app
        // is then evidence of which code is running, instead of a guess.
        val gitSha = runCatching {
            val p = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
            p.inputStream.bufferedReader().readText().trim().ifEmpty { "nogit" }
        }.getOrDefault("nogit")
        versionName = "0.9.36"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
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
        // setting — just to accept an update. A committed DEBUG key has no
        // security value to lose (anyone can generate one; it never
        // authenticates anything), and it makes every future build — CI,
        // sandbox, fork — signature-compatible with the installed base.
        // One-time cost of the switch: ONE final uninstall (from the
        // dead-machine key to this one). Never again after that.
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
 * H-5 / M-4: the bundled source mirror (assets/source) is what the healer's
 * LLM reads, so it must match the code that is running.
 *
 * M-4 (code review, 2026-11): this used to run inside preBuild, which meant a
 * stale committed mirror was silently rewritten before tests — the mirror test
 * could never fail, and the committed tree never showed the drift. Syncing is
 * now a manual step, and the build VERIFIES the committed mirror instead:
 *
 *   ./gradlew :app:syncSourceMirror     # after editing src/main/java
 *   ./gradlew :app:verifySourceMirror   # runs before every build and test run
 */
tasks.register<Sync>("syncSourceMirror") {
    from("src/main/java")
    into("src/main/assets/source")
}

/**
 * M-4: fails the build when the committed mirror is missing, different or
 * holds deleted files. Never writes: the fix is always syncSourceMirror.
 * Every file is compared, not just .kt, because Sync copies everything.
 */
tasks.register("verifySourceMirror") {
    // No declared outputs: the comparison must run every time, not be skipped
    // as up-to-date when only the sources changed.
    outputs.upToDateWhen { false }
    doLast {
        val live = file("src/main/java")
        val mirror = file("src/main/assets/source")
        fun relativeFiles(root: java.io.File): Map<String, java.io.File> =
            root.walkTopDown()
                .filter { it.isFile }
                .associateBy { it.relativeTo(root).invariantSeparatorsPath }
        val liveFiles = relativeFiles(live)
        val mirrorFiles = relativeFiles(mirror)
        val missing = liveFiles.keys - mirrorFiles.keys
        val orphan = mirrorFiles.keys - liveFiles.keys
        val differing = (liveFiles.keys intersect mirrorFiles.keys)
            .filter { liveFiles.getValue(it).readBytes().contentEquals(mirrorFiles.getValue(it).readBytes()) == false }
        if (missing.isNotEmpty() || orphan.isNotEmpty() || differing.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("assets/source is out of date with src/main/java (M-4).")
                    if (missing.isNotEmpty()) appendLine("  missing from the mirror: $missing")
                    if (differing.isNotEmpty()) appendLine("  differs from the source: $differing")
                    if (orphan.isNotEmpty()) appendLine("  in the mirror but deleted from the source: $orphan")
                    appendLine("  run ./gradlew :app:syncSourceMirror and commit the result.")
                },
            )
        }
    }
}

tasks.named("preBuild") { dependsOn("verifySourceMirror") }
// testDebugUnitTest does not depend on preBuild, so wire it explicitly.
tasks.matching { it.name.startsWith("test") }.configureEach {
    dependsOn("verifySourceMirror")
}

/**
 * The computer has to be inside the APK.
 *
 * Every build from 0.8.0 to 0.8.9 was uploaded without the payload (the
 * jniLibs/ and assets/forge/ trees are git-ignored and nothing fetched them in
 * CI), so the app could only report "this build has no Linux system" after the
 * owner had installed it. This check runs on the source tree before anything is
 * packaged: a build that would produce a computer-less APK fails here instead.
 *
 * tools/forge/verify-apk-payload.py does the same check on the finished APK,
 * because an ignore rule or a packaging change can drop files after this point.
 */
val requiredPayload = listOf(
    "src/main/jniLibs/arm64-v8a/libforge-proot.so",
    "src/main/jniLibs/arm64-v8a/libforge-proot-loader.so",
    "src/main/jniLibs/arm64-v8a/libtalloc.so",
    "src/main/jniLibs/arm64-v8a/libandroid-shmem.so",
    "src/main/jniLibs/arm64-v8a/libjvm.so",
    "src/main/assets/forge/linux/rootfs.tar",
    "src/main/assets/forge/linux/toolchain.json",
    "src/main/assets/forge/jvm/modules",
    "src/main/assets/forge/tools/d8.jar",
    "src/main/assets/forge/tools/android.jar",
    "src/main/assets/forge/tools/apksigner.jar",
)

/**
 * The payload is git-ignored on purpose (~250 MB), so a fresh clone — CI
 * included — has none of it. That is exactly why every GitHub build from
 * 0.9.0 on went red at checkPayload: the gate worked and the tree was empty.
 * The download therefore happens inside the build, from the same
 * sha256-pinned scripts a developer runs by hand, and only when something
 * is missing. `-PskipPayloadFetch` turns it off for an offline build that
 * means to fail loudly instead.
 */
fun payloadProblems(): List<String> {
    val missing = requiredPayload.filterNot { File(projectDir, it).isFile }
    val kotlinCompiler = File(projectDir, "src/main/assets/forge/tools")
        .listFiles { f -> f.name.startsWith("kotlin-compiler-embeddable-") }
        .orEmpty()
    return missing + if (kotlinCompiler.isEmpty()) {
        listOf("src/main/assets/forge/tools/kotlin-compiler-embeddable-*.jar")
    } else {
        emptyList()
    }
}

/** The SDK the payload copies android.jar / d8 / apksigner out of. */
val payloadSdkDir: String by lazy {
    val fromProp = File(rootDir, "local.properties").takeIf { it.isFile }?.readLines()
        ?.firstOrNull { it.startsWith("sdk.dir=") }
        ?.removePrefix("sdk.dir=")?.trim()
    sequenceOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), fromProp)
        .firstOrNull { !it.isNullOrBlank() && File(it).isDirectory }
        ?: "/usr/local/lib/android/sdk"
}

val provisionPayload = tasks.register<Exec>("provisionPayload") {
    description = "Fetches the in-app computer (proot, Alpine rootfs, JVM, on-phone toolchain) when the tree lacks it."
    group = "build"
    workingDir = rootDir
    environment("ANDROID_HOME", payloadSdkDir)
    environment("ANDROID_SDK_ROOT", payloadSdkDir)
    // The SDK repo and the Termux mirrors answer 429 under load (a CodeQL run
    // failed on exactly that). Each attempt is cheap and idempotent because the
    // scripts re-verify hashes, so retry the whole chain before failing.
    commandLine(
        "bash", "-c",
        """
        set -u
        for attempt in 1 2 3 4; do
          if ./tools/forge/ensure-sdk.sh && ./tools/forge/fetch-runtime.sh && ./tools/forge/fetch-linux.sh; then
            exit 0
          fi
          echo "payload fetch attempt ${'$'}attempt failed"
          sleep ${'$'}((attempt * 15))
        done
        echo "REFUSED: the payload could not be fetched after 4 attempts; see the errors above." >&2
        exit 1
        """.trimIndent()
    )
    onlyIf { payloadProblems().isNotEmpty() && !project.hasProperty("skipPayloadFetch") }
}

tasks.register("checkPayload") {
    description = "Fails the build if the in-app computer is not in the source tree."
    doLast {
        val problems = payloadProblems()
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The Linux payload is not in this tree; the APK would have no computer in it.\n" +
                    "Missing:\n" + problems.joinToString("\n") { "  $it" } +
                    "\nRun tools/forge/fetch-runtime.sh and tools/forge/fetch-linux.sh first, " +
                    "or drop -PskipPayloadFetch and let provisionPayload do it."
            )
        }
    }
}

tasks.named("checkPayload") { dependsOn(provisionPayload) }

tasks.named("preBuild") { dependsOn("checkPayload") }

/**
 * The forge's inventory is generated from the payload that is actually in
 * this tree, at build time.
 *
 * `ToolchainManifest.verify` was written, tested and then called by nobody:
 * `ForgeProbe` "Inventory" only tested that `files/forge/toolchain.manifest`
 * exists, and no build ever produced that file — so the gate could never pass
 * and the sha256 inventory the docs promise was never checked. Generated here
 * (23 files today) it matches the bytes shipped in THIS APK, and the gate
 * verifies them after provisioning.
 */
abstract class SyncToolchainManifestTask : DefaultTask() {
    @get:InputDirectory
    abstract val payloadRoot: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val root = payloadRoot.get().asFile
        val lines = mutableListOf("# Generated by :app:syncToolchainManifest from the payload in this tree. Do not edit.")
        listOf("tools", "jvm").forEach { sub ->
            val base = File(root, sub)
            if (base.isDirectory) {
                base.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                    lines += "${sub}/${f.relativeTo(base).invariantSeparatorsPath} ${sha256(f)} ${f.length()}"
                }
            }
        }
        val entries = lines.size - 1
        if (entries < 3) {
            throw GradleException("toolchain manifest would list $entries file(s); the payload tree is empty.")
        }
        val dest = File(outputDir.get().asFile, "forge/toolchain.manifest")
        dest.parentFile.mkdirs()
        dest.writeText(lines.joinToString("\n") + "\n")
        logger.lifecycle("toolchain.manifest: $entries payload file(s)")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

val forgeAssetsDir = layout.buildDirectory.dir("generated/forge/assets")

val syncToolchainManifest = tasks.register<SyncToolchainManifestTask>("syncToolchainManifest") {
    description = "Writes assets/forge/toolchain.manifest (name + sha256 + size) from the payload in the tree."
    group = "build"
    payloadRoot.set(layout.projectDirectory.dir("src/main/assets/forge"))
    outputDir.set(forgeAssetsDir)
}

tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
    dependsOn("syncToolchainManifest")
}

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
    // F-1 (review 2026-11-13): AGP 9 disables unit tests for the release
    // variant by default, and Compose Preview refuses to build its resource
    // task for a variant without them — so `./gradlew tasks`, IDE sync and
    // any `build` that materializes all tasks failed at configuration time
    // (test/assembleDebug never touched that path, which is why CI was
    // green). Enabling release unit tests is the supported fix; it also gives
    // the suite a second, minify-relevant compile target.
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }

    onVariants { variant ->
        val v = variant.name
        val capitalized = v.replaceFirstChar { it.uppercase() }
        val packageHealerClasspathVariant = tasks.register<Jar>("packageHealerClasspath$capitalized") {
            archiveFileName.set("healer/app_classes.jar")
            destinationDirectory.set(healerAssetsDir.map { it.dir("$v/") })
            // The compiled app classes (Kotlin output + generated BuildConfig)
            // of THIS variant, so a patch compiles against what actually runs.
            //
            // F-8 (2026-11-13): these paths were `tmp/kotlin-classes/$v` and
            // `generated/source/buildConfig/$v` — the legacy kotlin-android
            // output. AGP 9's built-in Kotlin writes to
            // intermediates/built_in_kotlinc/, so the jar shipped with the APK
            // contained ONLY host/vorv/BuildConfig.java (5 entries, no
            // classes) and every on-device patch compile failed with
            // unresolved references. Verified: 531 classes at the path below.
            from(layout.buildDirectory.dir("intermediates/built_in_kotlinc/$v/compile${capitalized}Kotlin/classes"))
            from(layout.buildDirectory.dir("intermediates/javac/$v/compile${capitalized}Java/classes"))
            from(layout.buildDirectory.dir("generated/source/buildConfig/$v"))
            dependsOn("compile${capitalized}Kotlin")
            // A self-healing classpath that is empty is worse than no classpath
            // at all: it fails on the phone, silently, at compile time.
            doLast {
                val jar = archiveFile.get().asFile
                val entries = ZipFile(jar).use { z -> z.entries().toList().map { it.name } }
                val required = listOf("host/vorv/tools/Tool.class", "host/vorv/kernel/", "host/vorv/llm/")
                val missing = required.filter { needle -> entries.none { it.startsWith(needle) } }
                if (missing.isNotEmpty()) {
                    throw GradleException(
                        "healer classpath jar ${jar.name} is incomplete: missing ${missing.joinToString()}. " +
                            "The self-repair pipeline would fail on-device (see REVIEW.md F-8)."
                    )
                }
            }
        }
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
            syncToolchainManifest,
            SyncToolchainManifestTask::outputDir,
        )
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
    implementation(libs.commons.compress)
    implementation(libs.xz)
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

// Robolectric downloads its own android-all jar from repo1.maven.org, ignoring
// the repositories in settings.gradle.kts. On 2026-11-25 that fetch threw
// IOException on the CI runner and every Robolectric class failed at
// classMethod, so the build went red without naming a defect in the app. Send
// it to the same Google Central mirror the rest of the build uses;
// robolectric.org/configuring documents both property names and confirms
// repo1.maven.org is the default that failed.
tasks.withType<Test>().configureEach {
    systemProperty(
        "robolectric.dependency.repo.url",
        "https://maven-central.storage-download.googleapis.com/maven2",
    )
    systemProperty("robolectric.dependency.repo.id", "google-central-mirror")
}
