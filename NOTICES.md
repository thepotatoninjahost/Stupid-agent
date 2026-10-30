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

Source: https://android.googlesource.com/

## Kotlin
- **org.jetbrains.kotlin:kotlin-stdlib** — Apache 2.0
- **org.jetbrains.kotlinx:kotlinx-coroutines-core, kotlinx-coroutines-android** — Apache 2.0

Source: https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines

## Networking
- **com.squareup.okhttp3:okhttp, logging-interceptor** — Apache 2.0
- **com.squareup.retrofit2:retrofit, converter-gson** — Apache 2.0

Source: https://github.com/square/okhttp, https://github.com/square/retrofit

## JSON
- **com.google.code.gson:gson** — Apache 2.0 (transitive via Retrofit)

Source: https://github.com/google/gson

## JavaScript engine
- **org.mozilla:rhino** — Mozilla Public License 2.0

Source: https://github.com/mozilla/rhino

## Image loading
- **io.coil-kt:coil-compose** — Apache 2.0

Source: https://github.com/coil-kt/coil

## Android Gradle Plugin
- **com.android.tools.build:gradle** — Apache 2.0

Source: https://android.googlesource.com/platform/tools/base/+/main/build-system/

## KSP (Kotlin Symbol Processing)
- **com.google.devtools.ksp:symbol-processing-api** — Apache 2.0

Source: https://github.com/google/ksp

---

## No proprietary dependencies

Vorv's source code is MIT-licensed. Every library Vorv depends on is open-source. The optional cloud LLM endpoint (`api.z.ai`) is called client-side over HTTPS — no Z.ai SDK is bundled in the app.

## No code copied from other repos

All Kotlin source in `app/src/main/java/host/vorv/` is original, written for this project. Standard library usage (Compose, Room, OkHttp, etc.) follows public documentation.
