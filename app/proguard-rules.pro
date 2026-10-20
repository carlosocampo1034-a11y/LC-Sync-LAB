# Keep Room entities and DAOs
-keep class com.lc.technician.** { *; }
-keepattributes *Annotation*

# Keep AppCompat
-keep class androidx.appcompat.** { *; }

# Avoid stripping Room
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**
