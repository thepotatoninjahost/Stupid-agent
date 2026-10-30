# NOVA Agent — proguard rules
# Keep Rhino classes (JS engine)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.**$* { *; }

# Keep Retrofit / OkHttp
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep Room generated code
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# Keep Compose
-keep class androidx.compose.** { *; }
