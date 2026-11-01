# Vorv — Third-party notices

Vorv uses the following open-source libraries. Each is included under its respective license.

## AndroidX
- **androidx.core:core-ktx** — Apache 2.0
- **androidx.lifecycle:lifecycle-runtime-ktx, lifecycle-viewmodel-compose** — Apache 2.0
- **androidx.activity:activity-compose** — Apache 2.0
- **androidx.compose:compose-bom** — Apache 2.0
- **androidx.compose.ui:ui, ui-graphics, ui-tooling, ui-tooling-preview** — Apache 2.0
- **androidx.compose.material3:material3** — Apache 2.0
- **androidx.compose.material:material-icons-extended** — Apache 2.0
- **androidx.navigation:navigation-compose** — Apache 2.0
- **androidx.room:room-runtime, room-ktx, room-compiler** — Apache 2.0
- **androidx.work:work-runtime-ktx** — Apache 2.0
- **androidx.documentfile:documentfile** — Apache 2.0
- **androidx.datastore:datastore-preferences** — Apache 2.0
- **androidx.security:security-crypto** — Apache 2.0 (EncryptedSharedPreferences + Tink for at-rest key encryption)

Source: https://android.googlesource.com/

## Kotlin
- **org.jetbrains.kotlin:kotlin-stdlib** — Apache 2.0
- **org.jetbrains.kotlinx:kotlinx-coroutines-core, kotlinx-coroutines-android** — Apache 2.0

Source: https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines

## Networking
- **com.squareup.okhttp3:okhttp** — Apache 2.0

Source: https://github.com/square/okhttp

(The unused Retrofit and OkHttp logging-interceptor dependencies were removed in v0.6.0.)

## HTML parsing
- **org.jsoup:jsoup** — MIT

Source: https://github.com/jhy/jsoup

## JavaScript engine
- **org.mozilla:rhino** — Mozilla Public License 2.0

Source: https://github.com/mozilla/rhino

## Local LLM inference
- **com.qualcomm.qti:geniex-android** — BSD-3-Clause (Qualcomm's first-party on-device Gen-AI runtime for Android; bundles libggml-hexagon.so, libQnnHtpV81.so, libggml-opencl.so, libggml-cpu.so, libllama.so, libnpu_jni.so for the S25's Hexagon NPU + Adreno GPU + CPU)

Source: https://github.com/qualcomm/GenieX
Maven: https://repo1.maven.org/maven2/com/qualcomm/qti/geniex-android/0.10.0/
- Runtime downloads: **Qwen2.5-Coder-7B-Instruct GGUF (Q4_0)** or **Qwen2.5-Coder-3B-Instruct GGUF (Q4_0)** — Qwen license (Apache 2.0), downloaded at runtime from HuggingFace via GenieX's ModelManagerWrapper, not bundled in the APK.
- llama.cpp itself: MIT — https://github.com/ggml-org/llama.cpp (used internally by GenieX's llama_cpp plugin)
- Qualcomm QNN/QAIRT runtime libs (vendored in the GenieX AAR under permissive OSS licenses per the AAR's NOTICE file)

## Python runtime
- **com.chaquo.python (Chaquopy)** — MIT / Apache 2.0 (Chaquopy is open-source since v11)

Source: https://github.com/chaquo/chaquopy

## Android Gradle Plugin
- **com.android.tools.build:gradle** — Apache 2.0

Source: https://android.googlesource.com/platform/tools/base/+/main/build-system/

## KSP (Kotlin Symbol Processing)
- **com.google.devtools.ksp:symbol-processing-api** — Apache 2.0

Source: https://github.com/google/ksp

## Test-only dependencies (not shipped in the APK)
- **junit:junit** — EPL 1.0
- **org.robolectric:robolectric** — MIT
- **androidx.test:core** — Apache 2.0
- **org.jetbrains.kotlinx:kotlinx-coroutines-test** — Apache 2.0
- **com.squareup.okhttp3:mockwebserver** — Apache 2.0
- **androidx.room:room-testing** — Apache 2.0

---

## No proprietary dependencies

Vorv's source code is MIT-licensed. Every library Vorv depends on is open-source. The optional cloud LLM endpoint (`api.z.ai`) is called client-side over HTTPS — no Z.ai SDK is bundled in the app.

## No code copied from other repos

All Kotlin source in `app/src/main/java/host/vorv/` is original, written for this project. Standard library usage (Compose, Room, OkHttp, etc.) follows public documentation.
