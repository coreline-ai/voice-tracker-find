import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.thinktank.recorder.ondevice"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // Phase 1 targets the Samsung SM-S931N and keeps native packaging explicit.
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging.resources.excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/DEPENDENCIES",
        "/META-INF/LICENSE*",
        "/META-INF/NOTICE*",
    )

    testOptions {
        // Without an explicit androidTest target the standalone test APK inherits minSdk (26),
        // which opens Android 16's deprecated-target dialog over Compose device tests.
        targetSdk = libs.versions.targetSdk.get().toInt()
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(files("libs/llama-android-b10107-arm64.aar"))
    implementation(project(":litert-bridge"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.room.testing)
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

val verifyOnDeviceNativeArtifacts by tasks.registering {
    group = "verification"
    description = "Verify the pinned llama.cpp arm64 artifact."
    doLast {
        val expected = mapOf(
            file("libs/llama-android-b10107-arm64.aar") to
                "ee0934ae4288108a5e6976820dd51ae5558c51891e79bdf85e8d9af6104c7268",
            file("src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so") to
                "a79ff75fbe1c3813cc239037b458a7828298a90a5b77f5314056508eefdf72bc",
            file("src/main/jniLibs/arm64-v8a/libonnxruntime.so") to
                "994848008526a934dfb579ac773b00e5867929234852b061005d45aacaee9533",
        )
        expected.forEach { (artifact, checksum) ->
            check(artifact.isFile) { "Missing native artifact: ${artifact.path}" }
            check(sha256(artifact) == checksum) {
                "Native artifact checksum mismatch: ${artifact.path}"
            }
        }
    }
}

val verifyOnDeviceNetworkBoundary by tasks.registering {
    group = "verification"
    description = "Reject network clients outside the model installer boundary."
    doLast {
        val forbidden = listOf(
            "okhttp3.",
            "retrofit2.",
            "java.net.URL",
            "java.net.HttpURLConnection",
            "ReceiverApi",
            "WebSocket",
        )
        val violations = fileTree("src/main/kotlin") {
            include("**/*.kt")
            exclude("**/modelpack/**")
        }.flatMap { source ->
            val text = source.readText()
            forbidden.filter(text::contains).map { token -> "${source.path}: $token" }
        }
        check(violations.isEmpty()) {
            "On-device network boundary violation:\n${violations.joinToString("\n")}"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyOnDeviceNativeArtifacts, verifyOnDeviceNetworkBoundary)
}
