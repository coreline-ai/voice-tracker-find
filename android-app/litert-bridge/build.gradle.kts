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
        exclude(group = "org.jetbrains.kotlin")
    }
    implementation(libs.kotlin.reflect)
}
