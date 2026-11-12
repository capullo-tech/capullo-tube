package tech.capullo.tubecapullo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.capullo.tubecapullo.data.youtube.YtVideo

/** The views line on cards and rows: YouTube's compact count, the year, and what shows when
 *  either is unknown. */
class ViewsFormatTest {

    private fun video(views: Long, year: Int?) = YtVideo("id", "t", "u", 200, null, viewCount = views, uploadYear = year)

    @Test
    fun `counts read like YouTube's, rounded down at every unit boundary`() {
        val cases = listOf(
            0L to "0",
            999L to "999",
            1_000L to "1K",
            1_250L to "1.2K",
            9_999L to "9.9K",
            34_000L to "34K",
            999_999L to "999K",
            1_000_000L to "1M",
            1_200_000L to "1.2M",
            15_700_000L to "15M",
            9_170_884_003L to "9.1B",
        )
        cases.forEach { (count, text) -> assertEquals("$count", text, compactCount(count)) }
    }

    @Test
    fun `the line shows what is known, and nothing when neither is`() {
        assertEquals("1.2M views · 2019", viewsLine(video(1_200_000, 2019)))
        assertEquals("34K views", viewsLine(video(34_000, null)))
        assertEquals("1 view", viewsLine(video(1, null)))
        assertEquals("2019", viewsLine(video(-1, 2019)))
        assertNull(viewsLine(video(-1, null)))
    }
}
