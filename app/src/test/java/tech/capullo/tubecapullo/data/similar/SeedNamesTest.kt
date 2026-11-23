package tech.capullo.tubecapullo.data.similar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.tubecapullo.data.youtube.YtVideo

/** The probe's q2 misses (PLAN.md, PR 1 results) as regressions: each must yield a bare query. */
class SeedNamesTest {
    private fun queries(title: String, uploader: String?) = SeedNames.queries(YtVideo("id", title, uploader, 200, null))

    @Test
    fun `a clean Artist - Title upload is one query`() {
        assertEquals(
            listOf(NameQuery("Radiohead", "Creep")),
            queries("Radiohead - Creep", "Radiohead"),
        )
    }

    @Test
    fun `a featured artist is kept first, then dropped`() {
        assertEquals(
            listOf(
                NameQuery("Luis Fonsi", "Despacito ft. Daddy Yankee"),
                NameQuery("Luis Fonsi", "Despacito"),
            ),
            queries("Luis Fonsi - Despacito ft. Daddy Yankee", "Luis Fonsi"),
        )
    }

    @Test
    fun `bracketed text and an M-V tag go in the bare query, even without a space before the bracket`() {
        assertEquals(
            NameQuery("PSY", "GANGNAM STYLE"),
            queries("PSY - GANGNAM STYLE(강남스타일) M/V", "officialpsy").last(),
        )
        assertEquals(
            NameQuery("Mdou Moctar", "Afrique Victime"),
            queries("Mdou Moctar - Afrique Victime (Live on KEXP)", "KEXP").last(),
        )
        assertEquals(
            NameQuery("Ichiko Aoba", "Asleep Among Endives"),
            queries("Ichiko Aoba - Asleep Among Endives (アンディーヴと眠って)", "ICHIKO AOBA").last(),
        )
    }

    @Test
    fun `a joint credit is narrowed to its first artist`() {
        assertEquals(
            NameQuery("Grupo Frontera", "UN X100TO"),
            queries("Grupo Frontera x Bad Bunny - UN X100TO (Video Oficial)", "Grupo Frontera").last(),
        )
        assertEquals("Simon", SeedNames.firstArtist("Simon & Garfunkel"))
        assertEquals("J Balvin", SeedNames.firstArtist("J Balvin, Bad Bunny"))
        assertEquals("La Ley", SeedNames.firstArtist("La Ley ft. Ely Guerra"))
    }

    @Test
    fun `a Topic channel names the artist and the title is the song`() {
        val tries = queries("Blue Train (Remastered 2003/Rudy Van Gelder Edition)", "John Coltrane - Topic")
        assertEquals("John Coltrane", tries.first().artist)
        assertTrue(NameQuery("John Coltrane", "Blue Train") in tries)
    }

    @Test
    fun `tries are never repeated, whatever their case and punctuation`() {
        val tries = queries("Bad Bunny - Tití Me Preguntó", "Bad Bunny")
        assertEquals(tries.distinctBy { SeedNames.key(it.artist) to SeedNames.key(it.title) }, tries)
        assertEquals(1, tries.size)
    }

    @Test
    fun `a title with no artist anywhere gives no query`() {
        assertTrue(queries("!!!", null).isEmpty())
    }

    @Test
    fun `an artist named in the title or by a Topic channel is trusted, a label uploader is not`() {
        assertTrue(queries("Radiohead - Creep", "Radiohead").all { it.trusted })
        assertTrue(queries("Blue Train", "John Coltrane - Topic").first().trusted)
        val label = queries("\"Tum Hi Ho\" Aashiqui 2 Full Song With Lyrics | Aditya Roy Kapur", "T-Series")
        assertEquals("T-Series", label.first().artist)
        assertTrue(label.none { it.trusted })
    }
}
