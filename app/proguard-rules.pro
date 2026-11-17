# TIF (android.media.tv) - platform siniflari R8 tarafindan korunur
-keep class android.media.tv.** { *; }

# Compose
-dontwarn org.jetbrains.annotations.**

# Gson (kanal yedek JSON modeli)
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.makay.livetv.domain.model.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
