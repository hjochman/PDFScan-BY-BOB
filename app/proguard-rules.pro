# Project-specific ProGuard rules for PDFScan by Bob
# Full reference: http://developer.android.com/guide/developing/tools/proguard.html

# Keep OpenCV native bindings intact
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Keep FileProvider and related classes
-keep class androidx.core.content.FileProvider

# Keep BuildConfig so versionName is accessible at runtime
-keep class com.hape.pdfscan.BuildConfig { *; }
