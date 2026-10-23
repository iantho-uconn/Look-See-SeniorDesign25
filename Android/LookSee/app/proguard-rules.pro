# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in your project's gradle build scripts.

# Amplify
-keep class com.amplifyframework.** { *; }

# Models
-keep class looksee.angelll.com.models.** { *; }
-keepclassmembers class looksee.angelll.com.models.** { *; }

# AWS
-keep class com.amazonaws.** { *; }
