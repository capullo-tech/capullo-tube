package tech.capullo.tubecapullo.data.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The stored Trending chart list, including the move from the single-region pref. */
class ChartRegionsPrefTest {

    @Test
    fun `a single region from an earlier build becomes a one-chart list`() {
        assertEquals(listOf("NI"), decodeChartRegions(stored = null, legacy = "NI"))
    }

    @Test
    fun `no stored region at all is the device default`() {
        assertEquals(emptyList<String>(), decodeChartRegions(stored = null, legacy = null))
    }

    @Test
    fun `the stored list wins over a leftover single region, even when empty`() {
        assertEquals(listOf("NO", "US"), decodeChartRegions(stored = "NO,US", legacy = "NI"))
        assertEquals(emptyList<String>(), decodeChartRegions(stored = "", legacy = "NI"))
    }

    @Test
    fun `a list keeps its pick order through a write and a read`() {
        val picked = listOf("US", "NI", "NO")

        assertEquals(picked, decodeChartRegions(encodeChartRegions(picked), legacy = null))
    }
}
