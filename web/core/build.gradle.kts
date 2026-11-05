// Flowstead web core (D109). Same Kotlin, serialization and coroutines versions as android/gradle/libs.versions.toml.
// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
}

val repo = rootDir.resolve("../..")

// android/core is never modified (owner's decision, D109). The build copies it here and applies one patch,
// patches/core-web.patch, that swaps the few JVM-only calls for the browser versions in src/compat. The patch is
// applied with no fuzz: if the app's core changes where the patch touches, the build stops here instead of drifting.
val coreSrc = layout.buildDirectory.dir("core-src")
val syncCore = tasks.register<Sync>("copyCore") {
    from(repo.resolve("android/core/src/main/kotlin")) { into("main") }
    from(repo.resolve("android/core/src/test/kotlin")) { into("test") }
    into(coreSrc)
}
val patchCore = tasks.register<Exec>("patchCore") {
    dependsOn(syncCore)
    val patch = rootDir.resolve("patches/core-web.patch")
    inputs.file(patch)
    inputs.dir(repo.resolve("android/core/src"))
    outputs.dir(coreSrc)
    workingDir(coreSrc.get().dir("main").asFile)
    commandLine("patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", patch.absolutePath)
}

// The app's canvas code (D110), copied unchanged: its symbols (Icons.kt) and its geometry (sizes, ports, routes, snapping,
// hit-testing). They draw through a small stand-in for Compose's drawing API (src/composeShim) that paints on a browser
// canvas. If the app's files start using more of Compose than the stand-in has, this build fails instead of drifting.
val appCanvasSrc = layout.buildDirectory.dir("app-src")
val copyAppCanvas = tasks.register<Sync>("copyAppCanvas") {
    from(repo.resolve("android/app/src/main/kotlin")) {
        include("app/flowstead/ui/flowsheet/Icons.kt", "app/flowstead/ui/flowsheet/CanvasGeometry.kt")
    }
    into(appCanvasSrc)
}

kotlin {
    // JVM too: the app's own core tests run against this copy there, and every browser replacement is checked
    // against the Java API it replaces.
    jvm()
    js(IR) {
        outputModuleName = "flowstead-core"
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
        useEsModules()
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(coreSrc.map { it.dir("main") })
            kotlin.srcDir("src/compat/kotlin")
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        jvmMain { kotlin.srcDir("src/jvmCompat/kotlin") }
        jsMain {
            kotlin.srcDir("src/jsCompat/kotlin")
            kotlin.srcDir("src/jsApi/kotlin") // FlowsteadCore and FlowsheetView: the API the web app calls
            kotlin.srcDir("src/composeShim/kotlin")
            kotlin.srcDir(copyAppCanvas.map { appCanvasSrc.get() })
        }
        commonTest {
            kotlin.srcDir(layout.buildDirectory.dir("generated/testData"))
            dependencies { implementation(kotlin("test")) }
        }
        jvmTest {
            kotlin.srcDir(coreSrc.map { it.dir("test") }) // the app's own core tests, unchanged
            kotlin.srcDir("src/compatJvmTest/kotlin")
            resources.srcDir(layout.buildDirectory.dir("generated/help"))
            dependencies { implementation(kotlin("test-junit")) }
        }
    }
}

// As android/core's helpResources: the methods reference, release notes and examples, from the repository.
val helpResources = tasks.register<Sync>("helpResources") {
    into(layout.buildDirectory.dir("generated/help"))
    from(repo.resolve("docs/DECISIONS.md")) { into("help") }
    from(repo.resolve("docs/release-notes")) { into("help/release-notes") }
    from(repo.resolve("benchmarks")) {
        include("*/input.json")
        eachFile { path = "help/examples/${relativePath.segments[0]}.json" }
        includeEmptyDirs = false
    }
    doLast {
        val help = destinationDir.resolve("help")
        val names = help.walkTopDown().filter { it.isFile && it.name != "index.txt" }.map { it.relativeTo(help).invariantSeparatorsPath }.sorted()
        help.resolve("index.txt").writeText(names.joinToString("\n"))
    }
}
tasks.named("jvmTestProcessResources") { dependsOn(helpResources) }

tasks.withType<Test>().configureEach {
    systemProperty("flowstead.sharedDir", repo.resolve("shared").canonicalPath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT }
}

// The shared test data as Kotlin constants, so the cross-platform tests run in JavaScript too (no file system there).
val testData = tasks.register("testData") {
    val shared = repo.resolve("shared")
    val bench = repo.resolve("benchmarks")
    inputs.dir(shared.resolve("units")); inputs.dir(shared.resolve("fixtures")); inputs.dir(bench)
    val out = layout.buildDirectory.file("generated/testData/app/flowstead/core/TestData.kt")
    outputs.file(out)
    doLast {
        fun lit(t: String) = "\"" + t.replace("\\", "\\\\").replace("\"", "\\\"").replace("\$", "\\\$")
            .replace("\r", "").replace("\n", "\\n") + "\""
        val cases = bench.listFiles()!!.filter { it.resolve("input.json").isFile }.sortedBy { it.name }
        val f = out.get().asFile
        f.parentFile.mkdirs()
        f.writeText(buildString {
            appendLine("// Generated by web/core/build.gradle.kts (testData) from shared/ and benchmarks/. Do not edit.")
            appendLine("package app.flowstead.core")
            appendLine("object TestData {")
            appendLine("    val unitVectors = " + lit(shared.resolve("units/test-vectors.json").readText()))
            for (n in listOf("solve-result-06", "solve-result-letdown", "solve-result-lines", "flowsheet-lines"))
                appendLine("    val `$n` = " + lit(shared.resolve("fixtures/$n.json").readText()))
            appendLine("    val benchmarks: List<Pair<String, String>> = listOf(")
            for (c in cases) appendLine("        " + lit(c.name) + " to " + lit(c.resolve("input.json").readText()) + ",")
            appendLine("    )")
            appendLine("}")
        })
    }
}
tasks.matching { it.name.startsWith("compileTestKotlin") }.configureEach { dependsOn(testData, patchCore) }
tasks.matching { it.name.startsWith("compileKotlin") || it.name.contains("SourcesJar") }.configureEach { dependsOn(patchCore) }
