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
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    versionCatalogs {
        create("libs") { from("com.github.capullo-tech:build-conventions:dab9931c094df9cb6af547d8abdf85c32b687f2d") }
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
