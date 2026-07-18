# 180726 Initial rules for R8 release obfuscation

# OpenCV's native library instantiates and calls its Java classes by name over JNI;
# renaming or stripping any of them breaks the bridge at runtime.
-keep class org.opencv.** { *; }
