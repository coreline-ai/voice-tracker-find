pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository {
                maven {
                    name = "oauthLlmLocal"
                    url = uri(rootDir.resolve("local-maven"))
                }
            }
            filter { includeGroup("ai.coreline.oauthllm") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "ai-r-voice-android"
include(":app")
include(":feature-ondevice")
include(":feature-cloud-summary")
include(":litert-bridge")
