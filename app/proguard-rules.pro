# R8 rules for EchoSonar.
# The app is pure Kotlin/Compose; AGP ships the Compose consumer rules.

# Keep line numbers for readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Audio/DSP core is exercised through reflection-free public APIs, nothing to keep.
-dontwarn org.jetbrains.annotations.**