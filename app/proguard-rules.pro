# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0

# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/joey/android/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
-keepclassmembers class org.lineageos.jelly.js.** {
    public *;
}

# Keep anything exposed to WebView via addJavascriptInterface
-keepclasseswithmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Intense optimization: strip verbose/debug log calls from release builds.
# Error and warning logs are kept for diagnostics.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep public class * extends android.support.design.widget.CoordinatorLayout$Behavior {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>();
}
