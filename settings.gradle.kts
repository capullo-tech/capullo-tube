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
        // capullo-audio(-ui) + build-conventions + NewPipeExtractor (all jitpack)
        maven { url = uri("https://jitpack.io") }
    }
    versionCatalogs {
        // Shared org toolchain, pinned by commit from jitpack (same pin as telecloud-radio).
        create("libs") { from("com.github.capullo-tech:build-conventions:22483910a0cd6d7e583ec3d268ad1c8f872bb4ba") }
        // Local pins: inter-repo capullo coordinates + this app's own deps.
        create("pins") { from(files("gradle/pins.versions.toml")) }
    }
}

// Dev: if the sibling capullo-audio checkout exists, build it from source and substitute it for its
// jitpack coordinate (local composite build). NOTE: on this machine the checkout lives at
// ../capullo-audio-src, which does NOT match - so builds here resolve the pinned jitpack commit.
// Clone/symlink to ../capullo-audio if engine co-development is ever needed (see HANDOFF-bootstrap §2).
if (file("../capullo-audio").exists()) {
    includeBuild("../capullo-audio") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-audio"))
                .using(project(":capullo-audio"))
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-audio-ui"))
                .using(project(":capullo-audio-ui"))
        }
    }
}

rootProject.name = "TubeCapullo"
include(":app")
