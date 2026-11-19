-dontobfuscate

-keepattributes SourceFile, LineNumberTable
-keepattributes *Annotation*

-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keepnames class paige.navic.**,dev.zt64.** { *; }
-keep class io.github.agonie0v0.melokeep.shared.** { *; }
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    static ** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
