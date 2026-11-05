// Flowstead web core (D109): a copy of android/core, adapted to compile for the browser.
// The Android app does not use this module; android/ is not modified. SPDX-License-Identifier: GPL-3.0-or-later
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "flowstead-web-core"
