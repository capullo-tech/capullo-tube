package tech.capullo.tubecapullo.data.similar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.tubecapullo.data.similar.LastfmClient.Companion.parseSimilarArtists
import tech.capullo.tubecapullo.data.similar.LastfmClient.Companion.parseSimilarTracks
import tech.capullo.tubecapullo.data.similar.LastfmClient.Companion.parseTopTracks

/** Bodies trimmed from live answers captured during the PR 1 probe (2026-11-18). */
class LastfmParseTest {
    @Test
    fun `similar tracks keep Last fm's order, artist, title and match`() {
        val body = """
            {"similartracks":{"track":[
              {"name":"No Surprises","playcount":1,"match":1.0,"artist":{"name":"Radiohead","mbid":"a"}},
              {"name":"Everlong","match":"0.506","artist":{"name":"Foo Fighters"}},
              {"name":"Where Is My Mind?","match":0.492,"artist":{"name":"Pixies"}}
            ],"@attr":{"artist":"Radiohead"}}}
        """
        assertEquals(
            listOf(
                SimilarTrack("Radiohead", "No Surprises", 1.0),
                SimilarTrack("Foo Fighters", "Everlong", 0.506),
                SimilarTrack("Pixies", "Where Is My Mind?", 0.492),
            ),
            parseSimilarTracks(body),
        )
    }

    @Test
    fun `a single result sent as an object, not an array, is one track`() {
        val body = """{"similartracks":{"track":{"name":"Wahdon","match":"1","artist":{"name":"Fairuz"}}}}"""
        assertEquals(listOf(SimilarTrack("Fairuz", "Wahdon", 1.0)), parseSimilarTracks(body))
    }

    @Test
    fun `an empty answer and Track not found are both no tracks`() {
        assertTrue(parseSimilarTracks("""{"similartracks":{"track":[],"@attr":{"artist":"PSY"}}}""").isEmpty())
        assertTrue(parseSimilarTracks("""{"similartracks":{"track":"","@attr":{}}}""").isEmpty())
        assertTrue(parseSimilarTracks("""{"error":6,"message":"Track not found","links":[]}""").isEmpty())
    }

    @Test
    fun `an invalid key or a rate limit is an error, not an empty list`() {
        val invalid = assertThrows(LastfmException::class.java) {
            parseSimilarTracks("""{"error":10,"message":"Invalid API key - You must be granted a valid key by last.fm"}""")
        }
        assertEquals(10, invalid.code)
        assertEquals(29, assertThrows(LastfmException::class.java) { parseSimilarTracks("""{"error":29,"message":"Rate Limit Exceeded"}""") }.code)
    }

    @Test
    fun `entries without a name or an artist are skipped`() {
        val body = """{"similartracks":{"track":[{"name":"","artist":{"name":"X"}},{"name":"Y"},{"name":"Ok","artist":{"name":"Z"}}]}}"""
        assertEquals(listOf(SimilarTrack("Z", "Ok", 0.0)), parseSimilarTracks(body))
    }

    @Test
    fun `similar artists and top tracks parse their own containers`() {
        assertEquals(
            listOf(SimilarArtist("Nirvana", 1.0), SimilarArtist("Muse", 0.87)),
            parseSimilarArtists("""{"similarartists":{"artist":[{"name":"Nirvana","match":"1"},{"name":"Muse","match":"0.87"}]}}"""),
        )
        assertEquals(
            listOf(SimilarTrack("Muse", "Hysteria", 0.0)),
            parseTopTracks("""{"toptracks":{"track":[{"name":"Hysteria","playcount":"9","artist":{"name":"Muse"}}]}}"""),
        )
    }
}
