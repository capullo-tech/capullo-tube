import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    // No kotlin.android: AGP 9.0+ ships built-in Kotlin (see RadioCapullo).
    alias(libs.plugins.kotlin.compose)
    // Station snapshots (station/StationSnapshot.kt) are @Serializable and stored as JSON.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "tech.capullo.tubecapullo"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.capullo.tubecapullo"
        // minSdk 26 per CONTRIBUTING §1 - and it's what lets us skip telecloud's core-library
        // desugaring: NewPipeExtractor's java.nio.file use is native on API 26+ (TC desugars only
        // because it is minSdk 24; see its pins.versions.toml).
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI signs with the preview keystore from RELEASE_* env, quantumcast's exact pattern
    // (.github/workflows/Build.yml decodes RELEASE_KEYSTORE_BASE64 to $RUNNER_TEMP). Absent or
    // empty env - local dev, fork PRs - leaves the release build UNSIGNED: green but
    // un-installable. Release.yml runs `apksigner verify` before publishing to reject that case.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")
        ?.let(::file)
        ?.takeIf { it.exists() && it.length() > 0L }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // compilerOptions, NOT jvmToolchain(17): mirrors telecloud/RadioCapullo (Kotlin 2.3 / AGP 9.x DSL).
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
        jniLibs {
            // The snapcast binaries (libsnapserver/libsnapclient/libsnapcontrol.so) are EXECUTED
            // via ProcessBuilder from nativeLibraryDir, not System.loadLibrary'd. Without legacy
            // packaging AGP leaves them inside the APK and exec fails with ENOENT (seen on-device
            // 2026-08-18). Same setting telecloud carries for the same reason.
            useLegacyPackaging = true
        }
    }
}

// Self-identifying APK copies: tubecapullo-v<versionName>-vc<versionCode>-<variant>.apk under
// build/outputs/apk-named/<variant>/, produced automatically after each assemble (CONTRIBUTING §1).
// Uses only the public artifacts API (SingleArtifact.APK) so it survives AGP upgrades.
androidComponents {
    onVariants { variant ->
        val vn = android.defaultConfig.versionName
        val vc = android.defaultConfig.versionCode
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val copyNamedApk = tasks.register<Copy>("copyNamedApk$cap") {
            from(variant.artifacts.get(SingleArtifact.APK)) {
                include("*.apk")
                rename { "tubecapullo-v$vn-vc$vc-${variant.name}.apk" }
            }
            into(layout.buildDirectory.dir("outputs/apk-named/${variant.name}"))
        }
        afterEvaluate { tasks.named("assemble$cap").configure { finalizedBy(copyNamedApk) } }
    }
}

// Room schema export: versioned JSON under app/schemas/ so future migrations are reviewable
// (Jetispot's convention; telecloud runs Room without schema export).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // The snapcast delivery engine's transport classes + capullo-audio-contracts (re-exported as
    // api). Pulled in at M1 on purpose: it validates the jitpack pin early and lands the contracts
    // jar in the Gradle cache so the SPI signatures can be read before M4 (HANDOFF-bootstrap §7).
    // Brings lib-snapcast-android (the native .so binaries) + ktor transitively.
    implementation(pins.capullo.audio)
    // Shared capullo UI: the multiroom SnapcastControlSheet (same sheet telecloud-radio uses).
    implementation(pins.capullo.audio.ui)

    ksp(libs.hilt.android.compiler)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Station snapshots: the saved queue of a station you left, as JSON in Room.
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(pins.androidx.material3)
    implementation(pins.androidx.compose.material.icons.core)
    implementation(pins.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(pins.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Local playback (M2). The FIFO tee variant arrives with the snapcast wiring in M3.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    // YouTube source: search, playlist/mix expansion and audio-stream resolution (data/youtube/).
    // Only the extractor library - the NewPipe *app* is not vendored. Brings Rhino transitively
    // for the player-JS deobfuscation.
    implementation(pins.newpipe.extractor)
    implementation(pins.okhttp) // backs the extractor's blocking Downloader SPI (see data/youtube/)
    implementation(pins.coil.compose) // home card art (search rows stay icon-only; see ResultRow)

    // Play history ("Because you played" on Home). Versions come from the shared catalog,
    // same as telecloud's Room setup.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(pins.mockk) // fakes YouTubeExtractorClient (concrete class) in station tests
    testImplementation(pins.sqlite.jdbc) // MigrationTest: the Room migrations on a real SQLite
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
