# =========================================================
# AggregatorX ProGuard Rules - v3.0.0
# =========================================================

# ---------------------
# App Data Models
# ---------------------
-keep class com.aggregatorx.app.data.model.** { *; }
-keepclassmembers class com.aggregatorx.app.data.model.** { *; }

# ---------------------
# Room Database
# ---------------------
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract <methods>;
}
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-dontwarn android.database.sqlite.SQLiteDatabase
-dontwarn android.database.Cursor

# ---------------------
# Jsoup HTML Parser
# ---------------------
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# ---------------------
# Kotlin Serialization
# ---------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.aggregatorx.app.**$$serializer { *; }
-keepclassmembers class com.aggregatorx.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.aggregatorx.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------
# OkHttp / Retrofit
# ---------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }

# ---------------------
# Hilt / Dagger
# ---------------------
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @javax.inject.** class * { *; }

# ---------------------
# Coroutines
# ---------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ---------------------
# Playwright (keep all since it's JVM-based)
# ---------------------
-keep class com.microsoft.playwright.** { *; }
-dontwarn com.microsoft.playwright.**

# ---------------------
# Coil Image Loading
# ---------------------
-dontwarn coil.**
-keep class coil.** { *; }

# ---------------------
# Media3 / ExoPlayer
# ---------------------
-dontwarn com.google.android.exoplayer2.**
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ---------------------
# Gson
# ---------------------
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# ---------------------
# General Android
# ---------------------
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------------------
# Crash Reporting - Keep stack traces readable
# ---------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------
# JNI (native TLS bridge binds by class + method name)
# ---------------------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class com.aggregatorx.app.engine.network.TlsClient { *; }
