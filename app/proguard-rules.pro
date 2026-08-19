# Minification is disabled for the personal-use build; these rules exist so that
# turning it on later does not silently break reflection-based paths.
-keep class androidx.media3.** { *; }
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
