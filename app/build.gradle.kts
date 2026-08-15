/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Patched build with: adblock (3 levels), JS dark mode, in-app tab switcher,
 * proper desktop mode, single-task activity (no recents clutter).
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "org.lineageos.jelly"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "16.21-patched"
    }

    // Two flavors of the same app:
    //  - patched: org.lineageos.jelly.patched (default, coexists with stock Jelly)
    //  - stock:   org.lineageos.jelly (drop-in replacement for the LineageOS
    //    system browser package; on ROMs with the system app preinstalled you
    //    must remove it first, e.g. `adb shell pm uninstall --user 0
    //    org.lineageos.jelly`, because the signature differs from the
    //    platform key)
    flavorDimensions += "variant"
    productFlavors {
        create("patched") {
            dimension = "variant"
            applicationId = "org.lineageos.jelly.patched"
        }
        create("stock") {
            dimension = "variant"
            applicationId = "org.lineageos.jelly"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "jellypatched"
            keyAlias = "jelly"
            keyPassword = "jellypatched"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".dev"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    lint {
        // Upstream ships partial translations; missing translations must not
        // fail the build (they fall back to English at runtime).
        disable += setOf("MissingTranslation")
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    annotationProcessor(libs.androidx.room.compiler)
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.transition)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
}
