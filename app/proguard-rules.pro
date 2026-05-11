# Keep Room entities
-keep class com.android.exe.data.entities.** { *; }

# Keep native JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep LlamaBridge TokenCallback (called from C++)
-keep interface com.android.exe.ai.LlamaBridge$TokenCallback { *; }
-keep class com.android.exe.ai.LlamaBridge { *; }

# Keep AndroidBridge for WebView JS interface
-keepclassmembers class com.android.exe.rendering.AvatarWebView$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
