# JNI Bridge Protection (verified against libbridge.so FindClass / GetMethodID lookups)
-keep class com.github.kr328.clash.core.bridge.** { *; }
-keep interface com.github.kr328.clash.core.bridge.** { *; }
-keepclasseswithmembers class * {
    native <methods>;
}

# Exact Kotlin stdlib & Coroutines symbols looked up via JNI from libbridge.so
-keep class kotlin.Unit {
    public static final kotlin.Unit INSTANCE;
}
-keep interface kotlinx.coroutines.CompletableDeferred {
    public abstract boolean complete(java.lang.Object);
    public abstract boolean completeExceptionally(java.lang.Throwable);
}

# Kotlin Serialization & Data Models
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep @kotlinx.serialization.Serializable class com.clashflux.android.model.** { *; }
-keepclassmembers class com.clashflux.android.model.** { *; }
-keepclassmembers class * extends kotlinx.serialization.internal.GeneratedSerializer { *; }

# Library consumer rules handle OkHttp / Coroutines / Serialization; suppress Android-absent desktop warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.yaml.snakeyaml.**
-dontwarn java.beans.**
