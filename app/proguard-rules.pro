# ============================================================================
# ImageNext ProGuard / R8 Rules
# Release hardening: obfuscation, shrinking, and optimization safety rules.
# ============================================================================

# ---- General Android ----
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep source file names and line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- Kotlin ----
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ---- Kotlin Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ---- Room Database ----
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface *
-keepclassmembers @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ---- OkHttp ----
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---- Coil Image Loading ----
-keep class coil3.** { *; }
-dontwarn coil3.**

# ---- Jetpack Compose ----
# Compose compiler handles most keep rules via metadata.
# Keep Compose runtime stability markers.
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ---- AndroidX Lifecycle ----
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.**

# ---- AndroidX Navigation ----
-keepnames class androidx.navigation.** { *; }

# ---- AndroidX DataStore ----
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# ---- AndroidX WorkManager ----
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# ---- ImageNext Domain Models ----
# Keep domain model classes used in serialization and Room entities
-keep class com.imagenext.core.model.** { *; }
-keep class com.imagenext.core.database.entity.** { *; }

# ---- Prevent stripping of security-critical classes ----
-keep class com.imagenext.core.security.** { *; }

# ---- Google ErrorProne (Tink dependency) ----
-dontwarn com.google.errorprone.annotations.**
