package tech.capullo.tubecapullo.data.similar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.tubecapullo.data.youtube.YtVideo

/** Cases from the PR 1 probe's PROBE cand lines. */
class ResolutionTest {
    private fun v(id: String, title: String, uploader: String?, seconds: Long = 240) = YtVideo(id, title, uploader, seconds, null)

    @Test
    fun `the official upload of the named song is picked`() {
        val results = listOf(
            v("other", "Foo Fighters - The Pretender", "Foo Fighters"),
            v("right", "Foo Fighters - Everlong (Official HD Video)", "Foo Fighters"),
        )
        assertEquals("right", pickResolution(results, "Foo Fighters", "Everlong")?.videoId)
    }

    @Test
    fun `a studio upload beats a live, cover or lyrics-video copy further up the results`() {
        val results = listOf(
            v("live", "Juan Gabriel - Hasta Que Te Conocí (En Vivo [Desde Bellas Artes])", "Juan Gabriel"),
            v("cover", "Hasta Que Te Conocí - Juan Gabriel (Cover Audio)", "Some Singer"),
            v("studio", "Juan Gabriel - Hasta Que Te Conocí (Audio)", "Juan Gabriel"),
        )
        assertEquals("studio", pickResolution(results, "Juan Gabriel", "Hasta Que Te Conocí")?.videoId)
    }

    @Test
    fun `a live version wins when it is the only match, or when Last fm asked for it`() {
        val live = v("live", "Panteón Rococó - La Carencia (En Vivo)", "Panteón Rococó")
        assertEquals("live", pickResolution(listOf(live), "Panteón Rococó", "La Carencia")?.videoId)
        assertEquals(0, versionPenalty("Song (Live)", "Song (Live)"))
        assertEquals(1, versionPenalty("Song (Live)", "Song"))
    }

    @Test
    fun `a Topic upload with a bare title matches through its uploader`() {
        val topic = v("topic", "Blue Train (Remastered 2003/Rudy Van Gelder Edition)", "John Coltrane - Topic")
        assertTrue(namesMatch(topic, "John Coltrane", "Blue Train"))
    }

    @Test
    fun `case, accents kept, and spacing do not break a match`() {
        assertTrue(namesMatch(v("x", "WIZKID  - OJUELEGBA", "StarBoy TV"), "Wizkid", "Ojuelegba"))
        assertTrue(namesMatch(v("x", "Los Enanitos Verdes - Lamento Boliviano", "Enanitos Verdes"), "Enanitos Verdes", "Lamento Boliviano"))
    }

    @Test
    fun `another song by the artist, or the song by someone else, is no match`() {
        assertFalse(namesMatch(v("x", "Radiohead - Karma Police", "Radiohead"), "Radiohead", "Creep"))
        assertFalse(namesMatch(v("x", "Creep - TLC (Official Video)", "TLC"), "Radiohead", "Creep"))
    }

    @Test
    fun `a match outside the radio's length window is not used`() {
        val hour = v("x", "Miles Davis - So What", "Miles Davis", seconds = 3600)
        assertNull(pickResolution(listOf(hour), "Miles Davis", "So What"))
    }
}
