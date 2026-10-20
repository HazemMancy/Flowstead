// Lets :core build on its own (no Android SDK needed): `gradle -p android/core test`.
// Inside the full Android build this file is ignored and android/settings.gradle.kts applies.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "core"
