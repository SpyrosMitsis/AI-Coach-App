# Minification is disabled for the MVP (see build.gradle.kts). These keep rules
# are in place so flipping isMinifyEnabled on later doesn't produce the classic
# release-only SerializationException on the first network call.

# --- kotlinx.serialization ---------------------------------------------------
# Keep the generated serializer machinery for every @Serializable class.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.workoutmaker.app.**$$serializer { *; }
-keepclassmembers class com.workoutmaker.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.workoutmaker.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- ktor / supabase-kt ------------------------------------------------------
-keep class io.ktor.** { *; }
-keep class io.github.jan.supabase.** { *; }
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
