# kotlinx.serialization: keep generated serializers for @Serializable classes (incl. nav routes).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }
# Ktor / OkHttp optional dependencies
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**
