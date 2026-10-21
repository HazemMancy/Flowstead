# Added to the release build only with -PtestMinified. Libraries shared by the app and the test runner
# are packaged in the app APK alone, so R8 must keep the ones the runner needs even if the app doesn't.
-keep class androidx.tracing.** { *; }
-keep class androidx.test.** { *; }
