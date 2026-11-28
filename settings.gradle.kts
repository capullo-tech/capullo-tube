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
        // capullo-tech coordinates exist only on jitpack. Without this, Gradle asks Maven Central for
        // them first, and CI has failed on Maven Central's 429 answers to those lookups.
        exclusiveContent {
            forRepository { maven { url = uri("https://jitpack.io") } }
            filter { includeGroupAndSubgroups("com.github.capullo-tech") }
        }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    versionCatalogs {
        create("libs") { from("com.github.capullo-tech:build-conventions:c772e4f9bdb4d16bd7141c3459dcf4863513c00c") }
        create("pins") { from(files("gradle/pins.versions.toml")) }
    }
}

if (file("../capullo-audio").exists()) {
    includeBuild("../capullo-audio") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-audio"))
                .using(project(":capullo-audio"))
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-audio-ui"))
                .using(project(":capullo-audio-ui"))
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-tunnel"))
                .using(project(":capullo-tunnel"))
        }
    }
}

rootProject.name = "TubeCapullo"
include(":app")
