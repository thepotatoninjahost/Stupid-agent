# Vorv — R8/ProGuard rules
# Release builds run with isMinifyEnabled = true + isShrinkResources = true.
# These rules keep everything that is reached via reflection or JNI.

# ---------------------------------------------------------------- Rhino (JS engine)
# Rhino is driven almost entirely by reflection — keep it whole.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.**$* { *; }
-dontwarn org.mozilla.javascript.**

# ---------------------------------------------------------------- OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# Keep the Dns interface implementations we install (NetworkGuard.PublicDns).
-keep class host.vorv.tools.NetworkGuard* { *; }

# ---------------------------------------------------------------- Gson
# Vorv builds JsonObject trees by hand (no reflective TypeToken binding),
# so only generic signature info needs preserving.
-keepattributes Signature
-keepattributes *Annotation*

# ---------------------------------------------------------------- Room
-keep class host.vorv.data.entity.** { *; }
-keep class host.vorv.data.dao.** { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------- GenieX SDK (Qualcomm)
# The SDK reaches its beans and JNI glue via reflection.
-keep class com.geniex.** { *; }
-keep class com.geniex.sdk.bean.** { *; }
-dontwarn com.geniex.**

# ---------------------------------------------------------------- Chaquopy (Python)
-keep class com.chaquo.** { *; }
-dontwarn com.chaquo.**

# ---------------------------------------------------------------- AndroidX Security (Tink)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ---------------------------------------------------------------- Coroutines
-dontwarn kotlinx.coroutines.debug.**

# ---------------------------------------------------------------- jsoup
# jsoup ships its own consumer rules; nothing extra required.

# ---------------------------------------------------------------- Vorv itself
# Tools are constructed directly (no reflection), but keep the Tool interface
# hierarchy so the sealed result types survive optimization.
-keep class host.vorv.tools.** { *; }
-keep class host.vorv.kernel.** { *; }
-keep class host.vorv.llm.** { *; }

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
