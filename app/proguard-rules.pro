# Keep WebView JS bridge methods & classes
-keepattributes *Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep public class com.fromsub.app.NativeBridge {
    *;
}
-keep public class com.fromsub.app.VideoBounds {
    *;
}

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# AndroidX Security Crypto / Tink
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
