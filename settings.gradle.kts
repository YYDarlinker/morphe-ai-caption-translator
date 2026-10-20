rootProject.name = "morphe-ai-caption-translator"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/MorpheApp/registry")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("app.morphe.patches") version "1.3.4"
}

include(":caption-contracts", ":caption-shared", ":translation-core")

include(":ui-preview")

settings {
    extensions {
        // Shrinks the extension: unused library code (Kotlin standard library) is dropped.
        proguardFiles(rootProject.projectDir.resolve("extensions/proguard-rules.pro").toString())
    }
}
