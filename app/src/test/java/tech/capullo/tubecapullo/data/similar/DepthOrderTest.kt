package tech.capullo.tubecapullo.data.similar

import org.junit.Assert.assertEquals
import org.junit.Test

class DepthOrderTest {
    private fun ranked(match: (Int) -> Double) = (1..40).map { SimilarTrack("a$it", "t$it", match(it)) }

    private fun ranks(tracks: List<SimilarTrack>) = tracks.map { it.title.drop(1).toInt() }

    @Test
    fun `Familiar keeps Last fm's order`() {
        assertEquals((1..40).toList(), ranks(depthOrder(ranked { 1.0 - it / 40.0 }, SimilarDepth.FAMILIAR)))
    }

    @Test
    fun `Deep starts past the familiar ranks, then falls back to them`() {
        val deep = ranks(depthOrder(ranked { 1.0 - it / 40.0 }, SimilarDepth.DEEP))
        assertEquals((DEPTH_FAMILIAR_RANKS + 1..40).toList() + (1..DEPTH_FAMILIAR_RANKS), deep)
    }

    @Test
    fun `Deep ignores how low the scores are, a list scored low past rank 1 still goes deep`() {
        // Redonditos - Musica para Pastillas: rank 1 at 1.0, everything after at 0.05 or less.
        val steep = ranked { if (it == 1) 1.0 else 0.05 / it }
        assertEquals(DEPTH_FAMILIAR_RANKS + 1, ranks(depthOrder(steep, SimilarDepth.DEEP)).first())
    }

    @Test
    fun `a short answer in Deep is still played, familiar ranks only`() {
        assertEquals(listOf(1, 2, 3, 4), ranks(depthOrder(ranked { 1.0 }.take(4), SimilarDepth.DEEP)))
    }
}
