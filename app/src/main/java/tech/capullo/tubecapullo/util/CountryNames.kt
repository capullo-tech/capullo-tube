package tech.capullo.tubecapullo.util

import java.util.Locale

/**
 * The display name of the ISO 3166-1 alpha-2 region [code] in the user's language, or the code
 * itself when the platform has no name for it (or the code is malformed).
 */
fun countryName(code: String): String = runCatching {
    Locale.Builder().setRegion(code).build().displayCountry
}.getOrNull()?.takeIf { it.isNotBlank() } ?: code
