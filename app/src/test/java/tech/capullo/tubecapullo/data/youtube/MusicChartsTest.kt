package tech.capullo.tubecapullo.data.youtube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicChartsTest {
    private fun fixture(name: String) = javaClass.getResource("/charts/$name.json")!!.readText()

    @Test
    fun `the US page yields its Trending 20 playlist, not the chart listed before it`() {
        // Captured 2026-11-23: "Top 100 Live Performances" comes first, then "Trending 20 United States".
        assertEquals(
            "OLAK5uy_kNWGJvgWVqlt5LsFDL9Sdluly4M8TvGkM",
            MusicCharts.trendingPlaylistId(fixture("music-charts-us")),
        )
    }

    @Test
    fun `the Global page has no trending chart`() {
        assertNull(MusicCharts.trendingPlaylistId(fixture("music-charts-global")))
    }

    @Test
    fun `an item linking anything but a playlist is skipped`() {
        val json = """
            {"items":[
              {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Trending artist"}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"UCabc"}}}},
              {"musicTwoRowItemRenderer":{"title":{"runs":[{"text":"Trending 20 "},{"text":"Japan"}]},
                "navigationEndpoint":{"browseEndpoint":{"browseId":"VLOLAK5uy_jp"}}}}
            ]}
        """.trimIndent()
        assertEquals("OLAK5uy_jp", MusicCharts.trendingPlaylistId(json))
    }

    @Test
    fun `the request asks for the region's page in English`() {
        val body = Json.parseToJsonElement(MusicCharts.requestBody("NI", "1.20251119.03.00")).jsonObject
        val client = body["context"]!!.jsonObject["client"]!!.jsonObject
        assertEquals("WEB_REMIX", client["clientName"]!!.jsonPrimitive.content)
        assertEquals("en", client["hl"]!!.jsonPrimitive.content)
        assertEquals("FEmusic_charts", body["browseId"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("NI"),
            body["formData"]!!.jsonObject["selectedValues"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
