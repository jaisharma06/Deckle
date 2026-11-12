# Deckle release shrinking rules (spec 07 §3.3).

# OpenCV's Java classes are reached from JNI.
-keep class org.opencv.** { *; }

# PDFBox-Android loads fonts and resources reflectively. Verify an export in a *release* build:
# this is the classic failure that only appears after minification.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.pdfbox.filter.JPXFilter
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# ML Kit ships its own consumer rules; add nothing unless a missing-class error proves otherwise.

# Strip debug/verbose logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
