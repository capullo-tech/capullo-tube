plugins {
    alias(libs.plugins.android.application) apply false
    // No kotlin.android alias: AGP 9.0+ ships built-in Kotlin (see RadioCapullo / telecloud-radio).
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
}

// Org code-style standard (mirrors telecloud-radio / RadioCapullo). `./gradlew spotlessApply` to
// format, `spotlessCheck` to verify.
spotless {
    kotlin {
        target("**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "ktlint_standard_annotation" to "disabled",
                    "max_line_length" to 100,
                    // Same relaxations QC/TC took: ktlint can't auto-expand Compose wildcard
                    // imports headless, and hand-wrapping long Compose lines isn't worth it.
                    // New code still respects 100 columns (CONTRIBUTING §7).
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    "ktlint_standard_max-line-length" to "disabled",
                ),
            )
    }
}
