# Keep all app classes
-keep class com.iromashka.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes RuntimeVisibleAnnotations
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Gson
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.TypeAdapter
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.TypeAdapter
-keep,allowobfuscation,allowshrinking interface com.google.gson.TypeAdapterFactory
-keep,allowobfuscation,allowshrinking class * implements com.google.gson.TypeAdapterFactory

# Retrofit
-keep,allowobfuscation,allowshrinking class retrofit2.** { *; }
-keep,allowobfuscation,allowshrinking class retrofit2.converter.gson.GsonResponseBodyConverter
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Kotlin
-dontwarn kotlin.**
-dontwarn kotlinx.**

# Misc
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
