# R8/ProGuard rules.
#
# Minification is disabled for both build types: the clone engine depends on
# reflective-free, plain Java APIs only, and keeping the release build
# unobfuscated makes the produced APK inspectable. If minification is enabled
# later, `:core` must be kept intact because it is loaded through the app's
# class loader exactly as compiled.
-dontwarn org.jetbrains.annotations.**
