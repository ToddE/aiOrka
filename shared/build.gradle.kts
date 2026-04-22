import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
        publishLibraryVariants("release")
    }

    jvm()

    js(IR) {
        browser()
        nodejs()
    }

    // iOS Targets — packaged as an XCFramework for Swift/Obj-C consumers
    val xcf = XCFramework("AiOrka")
    val iosTargets = listOf(iosX64(), iosArm64(), iosSimulatorArm64())
    iosTargets.forEach { target ->
        target.binaries.framework {
            baseName = "AiOrka"
            isStatic = true
            xcf.add(this)
        }
    }

    // Native targets for Go/Python distribution via C interop (.so / .dylib / .dll)
    linuxX64 { binaries { sharedLib { baseName = "aiorka" } } }
    macosArm64 { binaries { sharedLib { baseName = "aiorka" } } }
    macosX64 { binaries { sharedLib { baseName = "aiorka" } } }
    mingwX64 { binaries { sharedLib { baseName = "aiorka" } } }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.components.resources)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.serialization.json)
            implementation(libs.coroutines.core)
            implementation(libs.kaml)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.android)
        }

        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }

        jsMain.dependencies {
            implementation(libs.ktor.client.js)
        }

        // Shared Darwin source set: iOS + macOS (all Apple platforms)
        val darwinMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }
        iosX64Main.get().dependsOn(darwinMain)
        iosArm64Main.get().dependsOn(darwinMain)
        iosSimulatorArm64Main.get().dependsOn(darwinMain)
        macosArm64Main.get().dependsOn(darwinMain)
        macosX64Main.get().dependsOn(darwinMain)

        // Linux native
        val linuxX64Main by getting {
            dependencies {
                implementation(libs.ktor.client.curl)
            }
        }

        // Windows native
        val mingwX64Main by getting {
            dependencies {
                implementation(libs.ktor.client.winhttp)
            }
        }

        // C API surface — shared across all four native C-interop targets (not iOS)
        val nativeInteropMain by creating {
            dependsOn(commonMain.get())
        }
        linuxX64Main.get().dependsOn(nativeInteropMain)
        macosArm64Main.get().dependsOn(nativeInteropMain)
        macosX64Main.get().dependsOn(nativeInteropMain)
        mingwX64Main.get().dependsOn(nativeInteropMain)
    }
}

android {
    namespace = "org.aiorka"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "org.aiorka.generated"
    generateResClass = always
}
