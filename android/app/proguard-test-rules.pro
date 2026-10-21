# R8 rules for the instrumented-test APK when tests run against the minified release build (-PtestMinified).
# AndroidX Test refers to compile-only annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.lang.model.**
# The runner finds tests by class and method name.
-keep class app.flowstead.** { *; }
-keepattributes *Annotation*
