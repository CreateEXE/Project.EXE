-keepclassmembers class com.projectexe.bridge.AnimationBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.projectexe.bridge.AnimationBridge { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keep class com.projectexe.memory.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { abstract *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.sse.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-keepattributes *Annotation*, InnerClasses
-keep class com.projectexe.BuildConfig { *; }
-keep class com.projectexe.api.** { *; }
-keep class com.projectexe.ai.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
