-optimizationpasses 7
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively

-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

-renamesourcefileattribute x
-keepattributes !SourceFile,!LineNumberTable,!LocalVariable*

-keep public class com.replayx.sender.ui.MainActivity { public *; }

-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-keep class androidx.** { *; }
-dontwarn androidx.**
-keep class kotlin.** { *; }
-dontwarn kotlin.**
-dontwarn org.json.**
