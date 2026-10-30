# Vorv — proguard rules

# Keep Rhino classes (JS engine)
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.**$* { *; }

# Keep Retrofit / OkHttp
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Room
-keep class host.vorv.data.entity.** { *; }
