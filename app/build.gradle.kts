import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

// Last.fm read-only key: LASTFM_API_KEY in the environment (CI secret), else lastfm.apiKey in
// local.properties. Without one the app builds and similar songs stay off until the user adds
// a personal key in Settings.
val lastfmApiKey: String = System.getenv("LASTFM_API_KEY")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("local.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use(::load) }.getProperty("lastfm.apiKey") }
    ?: ""

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "tech.capullo.tubecapullo"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.capullo.tubecapullo"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "0.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "LASTFM_API_KEY", "\"$lastfmApiKey\"")
    }

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
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(pins.desugar.jdk.libs.nio)
    implementation(pins.capullo.audio)
    implementation(pins.capullo.audio.ui)

    ksp(libs.hilt.android.compiler)
    ksp(libs.room.compiler)
    implementation(libs.hilt.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
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

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    implementation(pins.newpipe.extractor)
    implementation(pins.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(pins.coil.compose)
    implementation(pins.reorderable)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(pins.mockk)
    testImplementation(pins.sqlite.jdbc)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(pins.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
