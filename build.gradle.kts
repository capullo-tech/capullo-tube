plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "ktlint_standard_annotation" to "disabled",
                    "max_line_length" to 100,
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    "ktlint_standard_max-line-length" to "disabled",
                ),
            )
    }
}
