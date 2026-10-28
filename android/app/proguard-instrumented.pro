# Added to the release build only with -PtestMinified. Libraries shared by the app and the test APK
# are packaged in the app APK alone, so R8 must keep what the test runner and the test need even
# where the app itself doesn't use it. The app's own classes stay shrunk and obfuscated.
-keep class androidx.tracing.** { *; }
-keep class androidx.test.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep class org.hamcrest.** { *; }
# The test calls the app's classes directly (e.g. constructs the on-device engine client that Hilt builds in
# the app), so their members must not be removed or rewritten. They are still renamed: the test APK is mapped
# to the obfuscated names, so the engine bridge and serialization are exercised as obfuscated.
-keep,allowobfuscation class app.thermodock.** { *; }
