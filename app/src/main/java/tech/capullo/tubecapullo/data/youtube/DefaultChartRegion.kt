package tech.capullo.tubecapullo.data.youtube

fun defaultChartRegion(localeCountry: String?, supported: Set<String>): String {
    val country = localeCountry?.trim()?.uppercase().orEmpty()
    if (country in supported) return country
    return CLOSEST_CHART_REGION[country]?.firstOrNull { it in supported } ?: FALLBACK_CHART_REGION
}

internal const val FALLBACK_CHART_REGION = "NI"

internal val CLOSEST_CHART_REGION: Map<String, List<String>> = mapOf(
    "TW" to listOf("JP", "KR"),
    "HK" to listOf("JP", "KR"),
    "MO" to listOf("JP", "KR"),
    "CN" to listOf("JP", "KR"),
    "MN" to listOf("KR", "JP"),
    "SG" to listOf("ID", "AU"),
    "MY" to listOf("ID", "AU"),
    "TH" to listOf("ID", "AU"),
    "VN" to listOf("ID", "AU"),
    "PH" to listOf("ID", "AU"),
    "KH" to listOf("ID", "AU"),
    "BN" to listOf("ID", "AU"),
    "PK" to listOf("IN"),
    "BD" to listOf("IN"),
    "LK" to listOf("IN"),
    "NP" to listOf("IN"),
    "QA" to listOf("AE", "SA"),
    "KW" to listOf("AE", "SA"),
    "BH" to listOf("AE", "SA"),
    "OM" to listOf("AE", "SA"),
    "JO" to listOf("SA", "EG"),
    "LB" to listOf("EG", "SA"),
    "IQ" to listOf("SA", "AE"),
    "LY" to listOf("EG"),
    "MA" to listOf("FR", "EG"),
    "DZ" to listOf("FR", "EG"),
    "TN" to listOf("FR", "EG"),
    "VE" to listOf("CO"),
    "PR" to listOf("US", "DO"),
    "SK" to listOf("CZ", "HU"),
    "GR" to listOf("IT", "TR"),
    "CY" to listOf("TR", "IL"),
    "BG" to listOf("RO", "RS"),
    "HR" to listOf("RS", "HU"),
    "BA" to listOf("RS"),
    "ME" to listOf("RS"),
    "MK" to listOf("RS"),
    "SI" to listOf("AT", "HU"),
    "LV" to listOf("EE"),
    "LT" to listOf("EE", "PL"),
    "BY" to listOf("RU", "UA"),
    "KZ" to listOf("RU"),
    "MD" to listOf("RO"),
    "MT" to listOf("IT", "GB"),
    "LI" to listOf("CH", "AT"),
    "GH" to listOf("NG"),
    "RW" to listOf("KE", "UG"),
    "ET" to listOf("KE"),
    "ZM" to listOf("ZW", "ZA"),
    "BW" to listOf("ZA"),
    "NA" to listOf("ZA"),
)
