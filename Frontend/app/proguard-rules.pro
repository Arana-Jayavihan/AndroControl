# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve line numbers for readable crash stack traces, but hide the source
# file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Gson ---
# Gson resolves model fields via reflection, so their names must not be renamed.
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Model classes serialized/deserialized with Gson (keep their fields intact).
-keep class com.aranaj.androcontrol.Server { <fields>; }