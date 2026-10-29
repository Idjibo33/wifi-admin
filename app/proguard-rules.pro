# R8 / ProGuard rules for the release build.

# The protocol models are plain Kotlin data classes used by Compose; keeping
# their names makes a crash report readable and costs almost nothing.
-keepnames class dev.wifiadmin.router.** { *; }

# org.json is provided by the Android platform at runtime. The JVM artifact
# used for the unit tests must not be packaged in the APK.
-dontwarn org.json.**
-dontwarn kotlin.**
