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
