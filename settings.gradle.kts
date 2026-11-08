pluginManagement {
    repositories {
        // 2026-11-08: Maven Central redirects cold fetches of GitHub-hosted
        // artifacts (e.g. kotlin-compiler-embeddable) to GitHub's release CDN,
        // which is returning 504 globally. Google's official read-only Central
        // mirror serves the same artifacts directly. Ordered FIRST so the
        // broken redirect path is never reached; google() stays for AGP etc.
        // Temporary — remove when GitHub's release CDN heals.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Same mirror-first ordering and reason as pluginManagement above.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2") }
        google()
        mavenCentral()
    }
}

rootProject.name = "Vorv"
include(":app")
