import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun secret(name: String): String? =
    providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: localProperties.getProperty(name)

val releaseStore = secret("THINKTANK_RELEASE_STORE_FILE")
// The physical-device debug build talks to the Mac mini receiver over LAN.
// Override this with THINKTANK_DEBUG_SERVER_URL for an emulator or another host.
val debugServerUrl = secret("THINKTANK_DEBUG_SERVER_URL") ?: "http://192.168.0.71:8765"
val hasReleaseSigning = listOf(
    releaseStore,
    secret("THINKTANK_RELEASE_STORE_PASSWORD"),
    secret("THINKTANK_RELEASE_KEY_ALIAS"),
    secret("THINKTANK_RELEASE_KEY_PASSWORD"),
).all { !it.isNullOrBlank() }

android {
    namespace = "com.thinktank.recorder.next"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.thinktank.recorder.next"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"\"")
        ndk {
            // Native STT/LLM support is intentionally fixed to the Samsung arm64 target.
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStore!!)
                storePassword = secret("THINKTANK_RELEASE_STORE_PASSWORD")
                keyAlias = secret("THINKTANK_RELEASE_KEY_ALIAS")
                keyPassword = secret("THINKTANK_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            buildConfigField(
                "String",
                "DEFAULT_SERVER_URL",
                "\"${debugServerUrl}\"",
            )
        }
        // Persistent Samsung preview package. Keep the historical `.qa` application ID so the
        // existing on-device database and downloaded models survive `adb install -r` updates.
        create("devicePreview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-preview"
            matchingFallbacks += listOf("debug")
        }
        // Disposable instrumentation target. Connected Gradle tasks may uninstall/reinstall only
        // this package and can never clear the persistent `.qa` preview data.
        create("deviceTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".deviceTest"
            versionNameSuffix = "-device-test"
            matchingFallbacks += listOf("debug")
        }
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Keep physical instrumentation isolated from both `.debug` and persistent `.qa` preview.
    testBuildType = "deviceTest"

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
        buildConfig = true
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
}

kapt {
    correctErrorTypes = true
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":feature-ondevice"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

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
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
}

val verifyRasterAssets by tasks.registering {
    group = "verification"
    description = "Verify bundled raster size, manifest entries and SHA-256 checksums."
    doLast {
        val assetDir = file("src/main/res/drawable-nodpi")
        val assets = assetDir.listFiles()
            ?.filter { it.extension == "webp" }
            .orEmpty()
        val manifest = rootProject.file("../docs/design/asset-manifest.json")
        check(manifest.isFile) { "asset-manifest.json is missing" }
        val manifestText = manifest.readText()
        val total = assets.sumOf { it.length() }
        check(total <= 2_621_440L) { "Bundled raster hard limit exceeded: $total" }
        assets.forEach { asset ->
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(asset.readBytes())
                .joinToString("") { "%02x".format(it) }
            val entry = Regex(
                """\{[^{}]*\"file\":\s*\"${Regex.escape(asset.name)}\"[^{}]*}""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(manifestText)?.value
            check(entry != null) {
                "${asset.name} is missing from asset manifest"
            }
            check(entry.contains("\"sha256\": \"$digest\"")) {
                "${asset.name} checksum does not match asset manifest"
            }
            listOf("author", "license", "prompt").forEach { field ->
                check(entry.contains("\"$field\":")) {
                    "${asset.name} is missing required $field provenance"
                }
            }
        }
        check(assets.size == 7) { "Expected 7 approved raster assets, found ${assets.size}" }
    }
}

val verifyBundledFontLicenses by tasks.registering {
    group = "verification"
    description = "Verify the full bundled-font notice is packaged with release resources."
    doLast {
        val notice = file("src/main/assets/licenses/FONT-LICENSES.txt")
        check(notice.isFile) { "bundled font license notice is missing" }
        val text = notice.readText()
        check("Pretendard" in text && "MaruBuri" in text) {
            "bundled font license notice is incomplete"
        }
        check("SIL OPEN FONT LICENSE Version 1.1" in text) {
            "bundled font license notice is missing OFL 1.1"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyRasterAssets, verifyBundledFontLicenses)
}
