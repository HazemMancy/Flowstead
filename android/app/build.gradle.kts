// Flowstead Android app. SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin); no org.jetbrains.kotlin.android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// On-device engine (decision D25): present when engine/embedded/android/prepare.sh has run.
val localEngineDir = rootDir.resolve("../engine/embedded/android")
val localEngine = localEngineDir.resolve("out/assets/engine/VERSION").exists()

val releaseKeystore: String? = System.getenv("FLOWSTEAD_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = "app.flowstead"
    compileSdk = 37

    // Shipped from their single sources: the privacy policy and the feature overview (shown under About)
    // and the compound library built from DWSIM's databases (engine/tools/compounds/build_catalogue.py).
    sourceSets.getByName("main").assets.srcDir("../../docs/privacy")
    sourceSets.getByName("main").assets.srcDir("../../docs/features")
    sourceSets.getByName("main").assets.srcDir("../../shared/compounds")
    if (localEngine) {
        sourceSets.getByName("main").assets.srcDir(localEngineDir.resolve("out/assets"))
        sourceSets.getByName("main").jniLibs.srcDir(localEngineDir.resolve("out/jniLibs"))
        externalNativeBuild { cmake { path = localEngineDir.resolve("CMakeLists.txt") } }
    }

    defaultConfig {
        applicationId = "app.flowstead"
        minSdk = 26
        targetSdk = 37
        versionCode = 19
        versionName = "1.8.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "LOCAL_ENGINE", localEngine.toString())
        // The engine's runtime is built for 64-bit ARM (phones) and x86_64 (emulators).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // A fixed, public debug key (not a secret) so every CI debug build is signed the same way and
    // installs as an update over the previous one. Release builds will use the owner's own key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The owner's release key (decision D34), from the environment: CI decodes the
        // FLOWSTEAD_KEYSTORE_BASE64 secret to a file. Store and key share one password (PKCS12).
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("FLOWSTEAD_KEYSTORE_PASSWORD")
                keyAlias = "flowstead"
                keyPassword = System.getenv("FLOWSTEAD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
            if (project.hasProperty("testMinified")) proguardFiles("proguard-instrumented.pro")
            // Without the owner's key a release build is signed with the debug key, so CI can test the
            // minified app; the release workflow refuses to publish such an APK.
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }
    // -PtestMinified: instrumented tests (the on-device benchmarks) run against the R8 release build.
    if (project.hasProperty("testMinified")) testBuildType = "release"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        // Versions are pinned deliberately (docs/STATUS.md); upgrades are done as a separate step.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    packaging {
        // Unpacked on install, so the .NET runtime can load native libraries by path.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("flowstead.repoDir", rootDir.parentFile.canonicalPath)
    val screenshots = layout.buildDirectory.dir("screenshots")
    systemProperty("flowstead.screenshotDir", screenshots.get().asFile.path)
    outputs.dir(screenshots) // so a cached test run still restores the images

    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.SHORT
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.ktor.client.mock)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
