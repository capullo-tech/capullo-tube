package tech.capullo.tubecapullo.data.similar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthOrderTest {
    /** Rank r has match 1.0 - r / 40, so ranks 37-40 fall under the Deep floor. */
    private val ranked = (1..40).map { SimilarTrack("a$it", "t$it", 1.0 - it / 40.0) }

    private fun ranks(tracks: List<SimilarTrack>) = tracks.map { it.title.drop(1).toInt() }

    @Test
    fun `Familiar keeps Last fm's order`() {
        assertEquals((1..40).toList(), ranks(depthOrder(ranked, SimilarDepth.FAMILIAR)))
    }

    @Test
    fun `Deep starts past the familiar ranks, skips weak matches, then falls back to the familiar ranks`() {
        val deep = depthOrder(ranked, SimilarDepth.DEEP)
        val strong = ranked.drop(DEPTH_FAMILIAR_RANKS).filter { it.match >= DEEP_MATCH_FLOOR }
        assertEquals(ranks(strong) + (1..DEPTH_FAMILIAR_RANKS), ranks(deep))
        assertEquals(DEPTH_FAMILIAR_RANKS + 1, ranks(deep).first())
        assertTrue("rank 40 (match 0.0) is too weak", 40 !in ranks(deep))
    }

    @Test
    fun `a short answer in Deep is still played, familiar ranks only`() {
        val short = ranked.take(4)
        assertEquals(listOf(1, 2, 3, 4), ranks(depthOrder(short, SimilarDepth.DEEP)))
    }
}
