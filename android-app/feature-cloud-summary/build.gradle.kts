import java.util.Properties
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

val oauthDefaultsFile = rootProject.file("oauth-llm.defaults.properties")
val oauthDefaults = Properties().apply {
    if (oauthDefaultsFile.isFile) oauthDefaultsFile.inputStream().use(::load)
}

fun setting(name: String): String =
    providers.gradleProperty(name).orNull
        ?: System.getenv(name)
        ?: localProperties.getProperty(name)
        ?: oauthDefaults.getProperty(name)
        ?: ""

fun quoted(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.coreline.ai.voice.cloudsummary"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "ANTHROPIC_CLIENT_ID", quoted(setting("AIRVOICE_ANTHROPIC_CLIENT_ID")))
        buildConfigField("String", "CODEX_CLIENT_ID", quoted(setting("AIRVOICE_CODEX_CLIENT_ID")))
        buildConfigField("String", "XAI_CLIENT_ID", quoted(setting("AIRVOICE_XAI_CLIENT_ID")))
        buildConfigField("String", "ANTHROPIC_MODEL", quoted(setting("AIRVOICE_ANTHROPIC_MODEL")))
        buildConfigField("String", "CODEX_MODEL", quoted(setting("AIRVOICE_CODEX_MODEL")))
        buildConfigField("String", "XAI_MODEL", quoted(setting("AIRVOICE_XAI_MODEL")))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

kapt { correctErrorTypes = true }

dependencies {
    implementation(project(":feature-ondevice"))
    implementation(libs.oauth.llm.android)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.json)
}

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes())
    .joinToString("") { "%02x".format(it) }

val verifyOAuthLlmArtifacts by tasks.registering {
    group = "verification"
    description = "Verify the pinned proprietary OAuth LLM SDK 0.1.0 artifacts."
    doLast {
        val expected = mapOf(
            rootProject.file("local-maven/ai/coreline/oauthllm/oauth-llm-api/0.1.0/oauth-llm-api-0.1.0.jar") to
                "6c4b7b58121e12e5101993bc25e233688be1a1ce639889b1875eaaaab9870de0",
            rootProject.file("local-maven/ai/coreline/oauthllm/oauth-llm-api/0.1.0/oauth-llm-api-0.1.0.pom") to
                "81a4e52182025bb0992d7868ccceac89a2785f901a75d299b502f17f71b7f459",
            rootProject.file("local-maven/ai/coreline/oauthllm/oauth-llm-android/0.1.0/oauth-llm-android-0.1.0.aar") to
                "63d5c1f1d9e3afc4756a73353e291a496513afa436379f48104a50c45de8cc08",
            rootProject.file("local-maven/ai/coreline/oauthllm/oauth-llm-android/0.1.0/oauth-llm-android-0.1.0.pom") to
                "8904d23a438df401dae3f3c696bbd28e2554825b6625b0d62a9df8b082e38c08",
        )
        expected.forEach { (artifact, checksum) ->
            check(artifact.isFile) { "Missing OAuth LLM artifact: ${artifact.path}" }
            check(sha256(artifact) == checksum) { "OAuth LLM checksum mismatch: ${artifact.path}" }
        }
    }
}

val verifyCloudSummaryBoundary by tasks.registering {
    group = "verification"
    description = "Reject feature-ondevice implementation imports from the cloud adapter."
    doLast {
        val violations = fileTree("src/main/kotlin") { include("**/*.kt") }.flatMap { source ->
            source.readLines().filter { line ->
                line.startsWith("import com.coreline.ai.voice.ondevice.") &&
                    !line.startsWith("import com.coreline.ai.voice.ondevice.api.")
            }.map { line -> "${source.path}: $line" }
        }
        check(violations.isEmpty()) {
            "Cloud summary crossed the on-device public API boundary:\n${violations.joinToString("\n")}"
        }
    }
}

val verifyTrackedOAuthDefaults by tasks.registering {
    group = "verification"
    description = "Verify source-controlled public OAuth registrations without confidential values."
    doLast {
        val required = setOf(
            "AIRVOICE_ANTHROPIC_CLIENT_ID",
            "AIRVOICE_ANTHROPIC_MODEL",
            "AIRVOICE_CODEX_CLIENT_ID",
            "AIRVOICE_CODEX_MODEL",
            "AIRVOICE_XAI_CLIENT_ID",
            "AIRVOICE_XAI_MODEL",
        )
        check(oauthDefaultsFile.isFile) { "Missing ${oauthDefaultsFile.path}" }
        required.forEach { name ->
            check(!oauthDefaults.getProperty(name).isNullOrBlank()) {
                "Missing public OAuth default: $name"
            }
        }
        val forbidden = oauthDefaults.stringPropertyNames().filter { name ->
            listOf("SECRET", "TOKEN", "AUTHORIZATION_CODE", "PKCE", "COOKIE")
                .any { marker -> marker in name.uppercase() }
        }
        check(forbidden.isEmpty()) {
            "Confidential OAuth fields are forbidden in tracked defaults: ${forbidden.joinToString()}"
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyOAuthLlmArtifacts, verifyCloudSummaryBoundary, verifyTrackedOAuthDefaults)
}
