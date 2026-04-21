# Keep all our classes (package: com.iromashka)
-keep class com.iromashka.** { *; }
-keep class com.iromashka.model.** { *; }
-keep class com.iromashka.network.RefreshRequest { *; }
-keep class com.iromashka.network.RefreshResponse { *; }
-keep class com.iromashka.storage.** { *; }
-keep class com.iromashka.viewmodel.** { *; }
-keep class com.iromashka.ui.** { *; }
-keep class com.iromashka.crypto.** { *; }
-keep class com.iromashka.service.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn okhttp3.**
-dontwarn okio.**
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**

# Retrofit + Gson: preserve generic type info for List<T> deserialization
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.TypeAdapter
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.TypeAdapter
-keep,allowobfuscation,allowshrinking interface com.google.gson.TypeAdapterFactory
-keep,allowobfuscation,allowshrinking class * implements com.google.gson.TypeAdapterFactory
-keep,allowobfuscation,allowshrinking class retrofit2.converter.gson.GsonResponseBodyConverter
