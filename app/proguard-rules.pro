# Kindleaf release (R8) rules.

# ── kotlinx.serialization (DTOs are reflected on by the Supabase client) ──
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.kindleaf.**$$serializer { *; }
-keepclassmembers class com.kindleaf.** {
    *** Companion;
}
-keepclasseswithmembers class com.kindleaf.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Supabase-kt / Ktor ──
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# ── Razorpay Checkout ──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep class com.razorpay.** { *; }
-dontwarn com.razorpay.**
-keep class proguard.annotation.Keep
-keep class proguard.annotation.KeepClassMembers
-keep @proguard.annotation.Keep class *
-keepclassmembers @proguard.annotation.KeepClassMembers class * { *; }
-optimizations !method/inlining/*

# ── Google Identity / Credentials ──
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn com.google.android.libraries.identity.googleid.**
