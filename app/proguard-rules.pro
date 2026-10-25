# Clockwise R8 rules.
#
# The default optimized Android rules already cover most AndroidX libraries. These rules pin the
# parts that are loaded reflectively (manifest components, Room generated code, ViewModels) and the
# JSON/HTTP stack used by the optional AI assistant.

# Keep line numbers for readable release crash reports without keeping local variable names.
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-renamesourcefileattribute SourceFile

# --- Android entry points (referenced from AndroidManifest.xml) ---
-keep class com.dinoe.clockwise.ClockwiseApp { *; }
-keep class com.dinoe.clockwise.MainActivity { *; }
-keep class com.dinoe.clockwise.alarm.AlarmActivity { *; }
-keep class com.dinoe.clockwise.alarm.AlarmReceiver { *; }
-keep class com.dinoe.clockwise.alarm.BootReceiver { *; }
-keep class com.dinoe.clockwise.alarm.AlarmService { *; }

# --- ViewModels (created reflectively by ViewModelProvider) ---
-keep class com.dinoe.clockwise.ui.*ViewModel { *; }

# --- Room ---
# Entities, DAOs and type converters are read reflectively by generated code.
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.dinoe.clockwise.data.AppDatabase_Impl { *; }

# --- kotlinx.serialization ---
# Clockwise currently uses the JSON tree API, but keep serializer entry points so any @Serializable
# model added later still works in a minified build.
-keep,includedescriptorclasses class com.dinoe.clockwise.**$$serializer { *; }
-keepclassmembers class com.dinoe.clockwise.** {
    *** Companion;
}
-keepclasseswithmembers class com.dinoe.clockwise.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- OkHttp / Okio ---
# These optional TLS providers are looked up at runtime and are not packaged with the app.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
