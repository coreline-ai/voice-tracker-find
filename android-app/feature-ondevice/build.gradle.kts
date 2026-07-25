import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)

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
    description = "Verify pinned sherpa-onnx and llama.cpp arm64 artifacts."
    doLast {
        val expected = mapOf(
            file("src/main/jniLibs/arm64-v8a/libonnxruntime.so") to
                "994848008526a934dfb579ac773b00e5867929234852b061005d45aacaee9533",
            file("src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so") to
                "a79ff75fbe1c3813cc239037b458a7828298a90a5b77f5314056508eefdf72bc",
            file("libs/llama-android-b10107-arm64.aar") to
                "96e22269f12a56d04be5577065d729677b0a61d606d38a8963d211a6cca4937c",
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
