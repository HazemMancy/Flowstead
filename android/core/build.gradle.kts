// ThermoDock core: pure-Kotlin model, units and formatting. SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
    // Shared cross-language unit-conversion vectors live at the repo root.
    systemProperty("thermodock.sharedDir", rootDir.resolve(if (rootDir.name == "core") "../../shared" else "../shared").canonicalPath)
}

// Help & Resources (D63): the methods reference, release notes and example flowsheets ship with the app, taken
// straight from the repository so the help always describes the code it is built with.
val helpResources = tasks.register<Sync>("helpResources") {
    val repo = rootDir.resolve(if (rootDir.name == "core") "../.." else "..")
    into(layout.buildDirectory.dir("generated/help"))
    from(repo.resolve("docs/DECISIONS.md")) { into("help") }
    from(repo.resolve("docs/release-notes")) { into("help/release-notes") }
    from(repo.resolve("benchmarks")) {
        include("*/input.json")
        eachFile { path = "help/examples/${relativePath.segments[0]}.json" }
        includeEmptyDirs = false
    }
    // Resources in a jar can't be listed: an index names them.
    doLast {
        val help = destinationDir.resolve("help")
        val names = help.walkTopDown().filter { it.isFile && it.name != "index.txt" }.map { it.relativeTo(help).invariantSeparatorsPath }.sorted()
        help.resolve("index.txt").writeText(names.joinToString("\n"))
    }
}
sourceSets.main { resources.srcDir(helpResources) }
