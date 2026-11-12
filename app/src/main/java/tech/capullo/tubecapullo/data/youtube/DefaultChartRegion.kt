package tech.capullo.tubecapullo.data.youtube

/**
 * The Trending chart the device default shows (owner decision 2026-11-12): [localeCountry] when
 * YouTube Charts serves it; else the first [supported] entry of its [CLOSEST_CHART_REGION] list;
 * else [FALLBACK_CHART_REGION]. A blank or missing locale country goes straight to the fallback
 * (this replaces the US default earlier builds used for the chart). Only a code in [supported]
 * is returned, unless [supported] lacks the fallback too.
 */
fun defaultChartRegion(localeCountry: String?, supported: Set<String>): String {
    val country = localeCountry?.trim()?.uppercase().orEmpty()
    if (country in supported) return country
    return CLOSEST_CHART_REGION[country]?.firstOrNull { it in supported } ?: FALLBACK_CHART_REGION
}

/** The chart for a device whose country neither YouTube Charts nor [CLOSEST_CHART_REGION]
 *  covers: the owner's choice (2026-11-12), not a measurement of anything. */
internal const val FALLBACK_CHART_REGION = "NI"

/**
 * For countries YouTube Charts does not serve (v0.26.5 serves 61, see
 * [YouTubeExtractorClient.supportedRegions]), the served countries to use instead, best first.
 * A GUESS by geography and language, never measured against what listeners there hear: nobody
 * has compared these charts. A wrong entry only means a less local default chart; the user can
 * still pick any chart in Settings.
 */
internal val CLOSEST_CHART_REGION: Map<String, List<String>> = mapOf(
    // East Asia
    "TW" to listOf("JP", "KR"),
    "HK" to listOf("JP", "KR"),
    "MO" to listOf("JP", "KR"),
    "CN" to listOf("JP", "KR"),
    "MN" to listOf("KR", "JP"),
    // South-east Asia
    "SG" to listOf("ID", "AU"),
    "MY" to listOf("ID", "AU"),
    "TH" to listOf("ID", "AU"),
    "VN" to listOf("ID", "AU"),
    "PH" to listOf("ID", "AU"),
    "KH" to listOf("ID", "AU"),
    "BN" to listOf("ID", "AU"),
    // South Asia
    "PK" to listOf("IN"),
    "BD" to listOf("IN"),
    "LK" to listOf("IN"),
    "NP" to listOf("IN"),
    // Gulf and Middle East
    "QA" to listOf("AE", "SA"),
    "KW" to listOf("AE", "SA"),
    "BH" to listOf("AE", "SA"),
    "OM" to listOf("AE", "SA"),
    "JO" to listOf("SA", "EG"),
    "LB" to listOf("EG", "SA"),
    "IQ" to listOf("SA", "AE"),
    // North Africa
    "LY" to listOf("EG"),
    "MA" to listOf("FR", "EG"),
    "DZ" to listOf("FR", "EG"),
    "TN" to listOf("FR", "EG"),
    // Latin America
    "VE" to listOf("CO"),
    "PR" to listOf("US", "DO"),
    // Europe
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
    // Sub-Saharan Africa
    "GH" to listOf("NG"),
    "RW" to listOf("KE", "UG"),
    "ET" to listOf("KE"),
    "ZM" to listOf("ZW", "ZA"),
    "BW" to listOf("ZA"),
    "NA" to listOf("ZA"),
)
