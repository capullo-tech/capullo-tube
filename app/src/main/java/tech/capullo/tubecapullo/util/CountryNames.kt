package tech.capullo.tubecapullo.util

import java.util.Locale

fun countryName(code: String): String = runCatching {
    Locale.Builder().setRegion(code).build().displayCountry
}.getOrNull()?.takeIf { it.isNotBlank() } ?: code
