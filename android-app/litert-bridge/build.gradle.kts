plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.thinktank.recorder.ondevice.summary.litert"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.litertlm.android) {
        // The bridge's Java API does not expose Kotlin types. Keep the application's pinned
        // Kotlin 1.9 runtime instead of letting LiteRT-LM's Kotlin 2.2 metadata enter KSP.
        exclude(group = "org.jetbrains.kotlin")
    }
    // Keep the application's compiler-compatible reflection runtime. The public LiteRT API used
    // by this bridge relies only on stable JVM signatures.
    implementation(libs.kotlin.reflect)
}
