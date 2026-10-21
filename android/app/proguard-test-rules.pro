# R8 rules for the instrumented-test APK when tests run against the minified release build (-PtestMinified).
# Only the app is meant to be minified: the test APK is left whole (AndroidX Test and JUnit load their
# runners by class name), and R8 only maps its references to the app's renamed classes.
-dontshrink
-dontoptimize
-dontobfuscate
# AndroidX Test refers to compile-only annotations.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.lang.model.**
