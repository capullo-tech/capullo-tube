package tech.capullo.tubecapullo.data.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * YouTube Music's Charts page (browseId FEmusic_charts), the source of Home's Trending row.
 *
 * charts.youtube.com, which NewPipe's trending-music kiosk reads, has answered 403 PERMISSION_DENIED
 * since 2026-11-18 unless the browse request carries a reCAPTCHA Enterprise token from its web app.
 * The Music Charts page still answers a plain WEB_REMIX request; its "Video charts" shelf links each
 * country's "Trending 20 <Country>" chart as a playlist, which [YouTubeExtractorClient] then reads.
 */
internal object MusicCharts {
    const val BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"

    /**
     * The countries the Charts page's country menu offers (Global aside), each with a Trending 20
     * chart, as captured on 2026-11-23. A country outside it gets the Global page, which has none.
     */
    val COUNTRIES: Set<String> = (
        "AE AR AT AU BE BO BR CA CH CL CO CR CZ DE DK DO EC EE EG ES FI FR GB GT HK HN HU ID IE IL " +
            "IN IS IT JP KE KR LU MX MY NG NI NL NO NZ PA PE PH PL PT PY RO RS RU SA SE SG SV TH TR " +
            "TW TZ UA UG US UY VN ZA ZW"
        ).split(" ").toSet()

    /** Asked for in English, so the chart titles [trendingPlaylistId] matches stay the same everywhere. */
    fun requestBody(region: String, clientVersion: String): String = buildJsonObject {
        putJsonObject("context") {
            putJsonObject("client") {
                put("clientName", "WEB_REMIX")
                put("clientVersion", clientVersion)
                put("hl", "en")
                put("gl", region)
            }
        }
        put("browseId", "FEmusic_charts")
        putJsonObject("formData") { putJsonArray("selectedValues") { add(region) } }
    }.toString()

    /** The playlist id of the page's trending chart, null when the page links none. */
    fun trendingPlaylistId(json: String): String? = twoRowItems(Json.parseToJsonElement(json))
        .firstNotNullOfOrNull { item ->
            val title = item.path("title", "runs")?.let(::runsText).orEmpty()
            val browseId = (item.path("navigationEndpoint", "browseEndpoint", "browseId") as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            browseId?.takeIf { title.startsWith(TRENDING_TITLE) && it.startsWith(PLAYLIST_BROWSE_PREFIX) }
                ?.removePrefix(PLAYLIST_BROWSE_PREFIX)
                ?.takeIf { it.isNotEmpty() }
        }

    private fun twoRowItems(element: JsonElement): Sequence<JsonObject> = sequence {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == TWO_ROW_ITEM && value is JsonObject) yield(value) else yieldAll(twoRowItems(value))
            }
            is JsonArray -> element.forEach { yieldAll(twoRowItems(it)) }
            else -> Unit
        }
    }

    private fun JsonObject.path(vararg keys: String): JsonElement? = keys.fold(this as JsonElement?) { node, key -> (node as? JsonObject)?.get(key) }

    private fun runsText(runs: JsonElement): String = (runs as? JsonArray).orEmpty().joinToString("") {
        ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty()
    }

    private const val TWO_ROW_ITEM = "musicTwoRowItemRenderer"
    private const val TRENDING_TITLE = "Trending"
    private const val PLAYLIST_BROWSE_PREFIX = "VL"
}
