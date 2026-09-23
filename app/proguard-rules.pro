# Add project specific ProGuard rules here.
# For more details, see https://developer.android.com/guide/developing/tools/proguard.html

# Keep line numbers for readable crash reports, hide the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Compose runtime relies on reflection-free code generation but its tooling
# annotations should be retained for inspection.
-keep class androidx.compose.runtime.** { *; }
