# Keep all app classes
-keep class com.iromashka.** { *; }
-keep class com.iromashka.model.** { *; }
-keep class com.iromashka.network.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepattributes *Annotation*

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class kotlin.Metadata { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# Retrofit
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class retrofit2.** { *; }

# Kotlin coroutines + suspend functions (критично для Retrofit suspend)
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.Unit
-dontwarn retrofit2.-KotlinExtensions
-dontwarn retrofit2.-KotlinExtensions$*

# Gson
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class com.google.gson.TypeAdapter
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.TypeAdapter
-keep,allowobfuscation,allowshrinking interface com.google.gson.TypeAdapterFactory
-keep,allowobfuscation,allowshrinking class * implements com.google.gson.TypeAdapterFactory
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# OkHttp
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# Sentry
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# Kotlin
-keep class kotlin.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn javax.annotation.**

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Misc
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
