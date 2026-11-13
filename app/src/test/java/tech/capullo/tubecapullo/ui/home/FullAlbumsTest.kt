package tech.capullo.tubecapullo.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.RELATED_MIN_VIEWS

/** The Full albums row's filters (docs/probes/home-v2-criteria.md section 3): the album window,
 *  the album test, the related gate, liked items left out, one card per album, and the
 *  fallback's artists. */
class FullAlbumsTest {

    private fun item(
        id: String,
        title: String = "Artist - Record (Full Album)",
        seconds: Long = 45 * 60L,
        verified: Boolean = true,
        views: Long = -1,
        uploader: String = "Artist",
    ) = YtVideo(id, title, uploader, seconds, null, viewCount = views, uploaderVerified = verified)

    private fun related(vararg videos: YtVideo, seedViews: Long = 1_000_000) = YtRelated(seedViews, videos.toList())

    private fun ids(videos: List<YtVideo>) = videos.map { it.videoId }

    @Test
    fun `the album window keeps 25 to 120 minutes, both ends included`() {
        val fetch = related(
            item("short", seconds = ALBUM_MIN_SECONDS - 1),
            item("min", seconds = ALBUM_MIN_SECONDS),
            item("max", seconds = ALBUM_MAX_SECONDS),
            item("long", seconds = ALBUM_MAX_SECONDS + 1),
            item("song", seconds = 240),
        )

        assertEquals(listOf("min", "max"), ids(fullAlbumCandidates(listOf(fetch), emptySet())))
    }

    @Test
    fun `an album title or chapters make an album, in any listed script`() {
        val titled = item("t", title = "Kind of Blue - Full Album")
        val spanish = item("es", title = "Buena Vista Social Club (Álbum Completo)")
        val japanese = item("ja", title = "青葉市子 アルバム")
        val chaptered = item("c", title = "Live at Juan-les-Pins (1967)")
        val neither = item("n", title = "Late night drive mix")
        val inWord = item("w", title = "Albumin and other proteins") // not the word "album"

        val albums = albumsOf(listOf(titled, spanish, japanese, chaptered, neither, inWord), setOf("c"))

        assertEquals(listOf("t", "es", "ja", "c"), ids(albums))
    }

    @Test
    fun `only untitled candidates spend a chapter check, at most the limit`() {
        val candidates = listOf(item("t1"), item("u1", title = "Live 1967")) +
            (2..5).map { item("u$it", title = "Session $it") }

        assertEquals(listOf("u1", "u2", "u3"), ids(chapterChecks(candidates, limit = 3)))
    }

    @Test
    fun `the related gate drops small unverified uploads, and a search has no seed share`() {
        val small = item("small", verified = false, views = RELATED_MIN_VIEWS - 1)
        val big = item("big", verified = false, views = 50_000)
        val official = item("official", verified = true)

        // Next to a 100M-view seed the share asks 1M: the 50K re-upload fails there...
        val nextToHit = related(small, big, official, seedViews = 100_000_000)
        assertEquals(listOf("official"), ids(fullAlbumCandidates(listOf(nextToHit), emptySet())))
        // ...and passes in a search, where only the floor applies.
        val search = related(small, big, official, seedViews = -1)
        assertEquals(listOf("big", "official"), ids(fullAlbumCandidates(listOf(search), emptySet())))
    }

    @Test
    fun `liked songs and liked albums are left out, and seeds interleave by rank, once each`() {
        val a = related(item("a1"), item("liked"), item("a3"))
        val b = related(item("b1"), item("a1"), item("b3"))

        val candidates = fullAlbumCandidates(listOf(a, b), excludedIds = setOf("liked"))

        assertEquals(listOf("a1", "b1", "a3", "b3"), ids(candidates))
    }

    @Test
    fun `one card per album, the verified upload else the most viewed, where the album first appears`() {
        val fan = item("fan", "LINKIN PARK - HYBRID THEORY [FULL ALBUM] HD", verified = false, views = 900_000, uploader = "Rock Classics")
        val meteora = item("met", "Linkin Park - Meteora (Full Album)", verified = false, views = 50_000, uploader = "Rock Classics")
        val official = item("off", "Hybrid Theory (20th Anniversary Edition) - Full Album", views = 20_000, uploader = "Linkin Park")
        val meteoraBig = item("met2", "Meteora [Full Album] 2003", verified = false, views = 2_000_000, uploader = "Rock Classics")

        val row = fullAlbumCards(listOf(fan, meteora, official, meteoraBig), artists = listOf("Linkin Park"))

        // The verified upload wins over 45x the views; between unverified ones, the views decide.
        assertEquals(listOf("off", "met2"), ids(row.cards))
        assertEquals(2, row.dupes)
    }

    @Test
    fun `the fallback searches the most liked artists, the newest like first on a tie`() {
        val likes = listOf(
            item("1", uploader = "Fairuz - Topic"),
            item("2", uploader = "Miles Davis"),
            item("3", uploader = "Fairuz"),
            item("4", uploader = "Björk"),
            item("5", uploader = "RammsteinVEVO"),
            item("6", uploader = "Rammstein"),
        )

        assertEquals(
            listOf("Fairuz", "Rammstein", "Miles Davis", "Björk").take(FULL_ALBUM_ARTISTS),
            fullAlbumArtists(likes),
        )
    }

    @Test
    fun `the album title test reads words, not letters inside words`() {
        assertTrue(hasAlbumTitle("THE ALBUM"))
        assertTrue(hasAlbumTitle("Greatest Hits (Full Album) 2026"))
        assertFalse(hasAlbumTitle("Albumin"))
        assertFalse(hasAlbumTitle("Late night playlist"))
    }
}
