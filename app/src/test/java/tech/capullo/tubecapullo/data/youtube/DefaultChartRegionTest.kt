package tech.capullo.tubecapullo.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

/** The device-default Trending chart: own country, closest served neighbour, then Nicaragua. */
class DefaultChartRegionTest {

    private val supported = setOf("JP", "KR", "ID", "AU", "NI", "US")

    @Test
    fun `a served locale country is its own chart`() {
        assertEquals("US", defaultChartRegion("US", supported))
    }

    @Test
    fun `Taiwan takes its closest served neighbour`() {
        assertEquals("JP", defaultChartRegion("TW", supported))
        assertEquals("KR", defaultChartRegion("TW", supported - "JP"))
    }

    @Test
    fun `a table entry whose candidates are all unserved falls back to Nicaragua`() {
        assertEquals(FALLBACK_CHART_REGION, defaultChartRegion("QA", supported))
        assertEquals("NI", FALLBACK_CHART_REGION)
    }

    @Test
    fun `a country outside the table falls back to Nicaragua`() {
        assertEquals("NI", defaultChartRegion("AQ", supported))
    }

    /** Replaces the US default earlier builds used when the locale named no country. */
    @Test
    fun `no locale country is Nicaragua, not the old US default`() {
        assertEquals("NI", defaultChartRegion(null, supported))
        assertEquals("NI", defaultChartRegion("", supported))
        assertEquals("NI", defaultChartRegion(" ", supported))
    }

    /** A candidate YouTube Charts does not serve is dead weight that hides a typo. The set is
     *  the extractor's YT_CHARTS_SUPPORTED_COUNTRY_CODES in v0.26.5 (javap, 2026-11-12). */
    @Test
    fun `every table candidate is a chart country, and no table key is one`() {
        val charts = (
            "AE AR AT AU BE BO BR CA CH CL CO CR CZ DE DK DO EC EE EG ES FI FR GB GT HN HU ID " +
                "IE IL IN IS IT JP KE KR LU MX NG NI NL NO NZ PA PE PL PT PY RO RS RU SA SE SV " +
                "TR TZ UA UG US UY ZA ZW"
            ).split(" ").toSet()
        assertEquals(61, charts.size)
        CLOSEST_CHART_REGION.forEach { (country, candidates) ->
            assertEquals(country, false, country in charts)
            assertEquals(country, emptyList<String>(), candidates - charts)
        }
    }
}
