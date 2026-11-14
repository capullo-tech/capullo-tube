package tech.capullo.tubecapullo.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.tubecapullo.data.history.LikedEntry
import tech.capullo.tubecapullo.data.history.PlayStat
import tech.capullo.tubecapullo.data.youtube.YtChapter
import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.Provenance
import tech.capullo.tubecapullo.station.RepeatMode
import tech.capullo.tubecapullo.station.SnapshotSlot
import tech.capullo.tubecapullo.station.StationSnapshot
import tech.capullo.tubecapullo.station.Why
import kotlin.random.Random

class HomeSectionsTest {
    private fun video(id: String, seconds: Long = 200) = YtVideo(id, "Title $id", "Uploader", seconds, null)

    private fun videos(vararg ids: String) = ids.map { video(it) }

    private fun keys(sections: List<HomeSection>) = sections.map { it.key }

    private fun chart(region: String, vararg ids: String) = TrendingFetch(region, videos(*ids))

    private fun failed(region: String, error: String) = TrendingFetch(region, emptyList(), error)

    private fun listenAgain(vararg ids: String) = HomeSection.ListenAgain(videos(*ids), emptySet())

    private fun station(
        id: Long,
        source: PlaySource = PlaySource.SongRadio(video("seed$id")),
        left: YtVideo = video("left$id"),
        positionMs: Long = 0,
        round: Int = 0,
        roundPos: Int = -1,
    ): StationSnapshot {
        val why = Why(Provenance.SEED, null, null)
        val slots = listOf(SnapshotSlot(left, why, false, false)) +
            (1..round).map { SnapshotSlot(video("r$id-$it"), why, false, false) }
        return StationSnapshot(
            id = id,
            savedAtEpochMs = id,
            source = source,
            slots = slots,
            left = 0,
            positionMs = positionMs,
            upcoming = emptyList(),
            history = emptyList(),
            dismissedIds = emptySet(),
            chainSeed = null,
            shuffle = false,
            repeat = RepeatMode.OFF,
            likesRound = emptyList(),
            roundPlayed = 0,
            collection = emptyList(),
            collectionWhy = null,
            round = (1..round).toList(),
            roundPos = roundPos,
            nextRound = null,
        )
    }

    @Test
    fun `with nothing personal the chart is the only section`() {
        val sections = homeSections(emptyList(), null, emptyList(), emptyList(), listOf(chart("NI", "t1")))

        assertEquals(listOf("trending-NI"), keys(sections))
    }

    @Test
    fun `rows come Continue, Listen again, More like, Full albums, then every chart in order`() {
        val sections = homeSections(
            continueCards = continueCards(listOf(station(1)), playingStationId = 0, max = 6),
            listenAgain = listenAgain("r1"),
            becauseLiked = listOf(
                HomeSection.BecauseLiked(video("newest"), videos("a")),
                HomeSection.BecauseLiked(video("older"), videos("b")),
            ),
            fullAlbums = videos("album1"),
            trending = listOf(chart("NI", "n1"), chart("NO", "o1"), chart("US", "u1")),
        )

        assertEquals(
            listOf(
                "continue",
                "listen-again",
                "liked-newest",
                "liked-older",
                "full-albums",
                "trending-NI",
                "trending-NO",
                "trending-US",
            ),
            keys(sections),
        )
    }

    @Test
    fun `a failed chart hides its row and no banner shows while another chart works`() {
        val trending = listOf(failed("NI", "Not served"), chart("NO", "o1"), failed("US", "x"))

        assertEquals(
            listOf("trending-NO"),
            keys(homeSections(emptyList(), null, emptyList(), emptyList(), trending)),
        )
        assertNull(trendingError(trending))
    }

    @Test
    fun `the banner shows the first error only when every chart failed`() {
        val trending = listOf(failed("NI", "Bot check"), failed("NO", "Rate limited"))

        assertEquals("Bot check", trendingError(trending))
        assertEquals(
            listOf("listen-again"),
            keys(homeSections(emptyList(), listenAgain("r1"), emptyList(), emptyList(), trending)),
        )
    }

    @Test
    fun `no charts fetched yet is not an error`() {
        assertNull(trendingError(emptyList()))
    }

    @Test
    fun `empty sections are left out`() {
        val sections = homeSections(
            continueCards = emptyList(),
            listenAgain = listenAgain(),
            becauseLiked = listOf(HomeSection.BecauseLiked(video("seed"), emptyList())),
            fullAlbums = emptyList(),
            trending = listOf(chart("NI"), chart("NO", "o1")),
        )

        assertEquals(listOf("trending-NO"), keys(sections))
    }

    @Test
    fun `continue leaves out the playing station and keeps the newest up to the setting`() {
        val stored = (5L downTo 1L).map { station(it) }

        val cards = continueCards(stored, playingStationId = 5, max = 3)

        assertEquals(listOf(4L, 3L, 2L), cards.map { it.stationId })
        assertEquals("left4", cards.first().video.videoId)
        assertTrue("0 hides the row", continueCards(stored, 5, max = 0).isEmpty())
    }

    @Test
    fun `the continue hint says the chapter, the list place, or the playhead`() {
        val chapters = listOf(YtChapter("A", 0), YtChapter("B", 60_000), YtChapter("C", 120_000))
        val album = PlaySource.Album(video("alb", seconds = 3600), chapters)
        assertEquals("Chapter 2 of 3", continueHint(station(1, album, positionMs = 90_000)))

        val playlist = PlaySource.Playlist("https://www.youtube.com/playlist?list=PLx", "Trip")
        assertEquals("Song 3 of 12", continueHint(station(2, playlist, round = 12, roundPos = 2)))

        assertEquals("1:23 of 3:20", continueHint(station(3, positionMs = 83_000)))
        val unknown = video("unknown", seconds = 0)
        assertEquals("0:05 in", continueHint(station(4, left = unknown, positionMs = 5_000)))
    }

    private val now = 1_000L * 24 * 60 * 60 * 1000
    private val day = 24 * 60 * 60 * 1000L

    private fun liked(id: String, daysAgo: Long, seconds: Long = 200) = LikedEntry(video(id, seconds), now - daysAgo * day)

    private fun played(id: String, times: Int, daysAgo: Long, seconds: Long = 200) = PlayStat(video(id, seconds), times, now - daysAgo * day)

    private fun pick(
        songs: List<LikedEntry> = emptyList(),
        albums: List<LikedEntry> = emptyList(),
        plays: List<PlayStat> = emptyList(),
        playingId: String? = null,
        continueIds: Set<String> = emptySet(),
        seed: Int = 1,
    ) = listenAgainCards(songs, albums, plays, now, playingId, continueIds, seed)

    private fun ids(row: HomeSection.ListenAgain) = row.cards.map { it.videoId }.toSet()

    @Test
    fun `liked songs and albums show only once 30 days passed since the like and the last play`() {
        val age = LISTEN_AGAIN_AGE_DAYS.toLong()
        val row = pick(
            songs = listOf(
                liked("old", age),
                liked("young", age - 1),
                liked("replayed", age + 50),
                liked("never", age + 1),
            ),
            albums = listOf(liked("oldAlbum", age + 5, seconds = 3000)),
            plays = listOf(played("old", 1, age), played("replayed", 1, age - 1)),
        )

        assertEquals(setOf("old", "never", "oldAlbum"), ids(row))
        assertEquals("albums are marked to play as albums", setOf("oldAlbum"), row.albumIds)
    }

    @Test
    fun `an unliked song needs 3 plays, none in the last 30 days, and the song length`() {
        val age = LISTEN_AGAIN_AGE_DAYS.toLong()
        val plays = LISTEN_AGAIN_MIN_PLAYS
        val row = pick(
            plays = listOf(
                played("often", plays, age),
                played("twice", plays - 1, age + 10),
                played("recent", plays + 5, age - 1),
                played("longVideo", plays, age, seconds = 3000),
            ),
        )

        assertEquals(setOf("often"), ids(row))
    }

    @Test
    fun `never the playing song nor a continue station's song or album`() {
        val age = LISTEN_AGAIN_AGE_DAYS.toLong()
        val row = pick(
            songs = listOf(liked("playing", age), liked("inContinue", age), liked("kept", age)),
            albums = listOf(liked("continueAlbum", age, seconds = 3000)),
            playingId = "playing",
            continueIds = setOf("inContinue", "continueAlbum"),
        )

        assertEquals(setOf("kept"), ids(row))
    }

    @Test
    fun `the pick is random per load, stable within one, and capped`() {
        val age = LISTEN_AGAIN_AGE_DAYS.toLong()
        val songs = (1..LISTEN_AGAIN_CARDS * 3).map { liked("s$it", age) }

        val first = pick(songs = songs, seed = 7).cards
        val again = pick(songs = songs.reversed(), seed = 7).cards
        val next = pick(songs = songs, seed = 8).cards

        assertEquals(LISTEN_AGAIN_CARDS, first.size)
        assertEquals("one load, one order", first, again)
        assertTrue("another load picks again", first != next)
    }

    @Test
    fun `continue ids are each card's song and, for an album, the album`() {
        val album = PlaySource.Album(video("alb", seconds = 3000))
        val cards = continueCards(listOf(station(1, album, left = video("alb")), station(2)), 0, 6)

        assertEquals(setOf("alb", "left2"), continueIds(cards))
    }

    private val seed = video("seed")

    private val noRelated = YtRelated(seedViews = -1, videos = emptyList())

    private fun cards(
        mix: List<YtVideo>,
        likedIds: Set<String> = emptySet(),
        related: YtRelated = noRelated,
    ) = becauseLikedCards(seed, mix, related, likedIds, Random(1)).map { it.videoId }

    @Test
    fun `liked row drops liked tracks and duplicates, keeping the Mix order`() {
        val mix = videos("seed", "a", "liked", "b", "a", "c")

        assertEquals(listOf("a", "b", "c"), cards(mix, likedIds = setOf("seed", "liked")))
    }

    @Test
    fun `liked row is capped after filtering, so liked tracks do not eat the cap`() {
        val liked = (1..CARDS_PER_ROW).map { "liked$it" }
        val fresh = (1..CARDS_PER_ROW + 5).map { "fresh$it" }

        assertEquals(
            fresh.take(CARDS_PER_ROW),
            cards((liked + fresh).map { video(it) }, likedIds = liked.toSet()),
        )
    }

    @Test
    fun `liked rows drop tracks outside the station duration window`() {
        val compilation = YtVideo("long", "Best of", "Uploader", 59 * 60L, null)
        val jingle = YtVideo("short", "Teaser", "Uploader", 30, null)
        assertEquals(listOf("a"), cards(listOf(compilation, video("a"), jingle)))
    }

    @Test
    fun `a liked row pairs the Mix with the related videos, at most CARDS_PER_ROW cards`() {
        val mix = (1..CARDS_PER_ROW).map { video("m$it") }
        val related = (1..CARDS_PER_ROW).map { video("r$it").copy(uploaderVerified = true) }

        val row = cards(mix, related = YtRelated(seedViews = 1_000_000, videos = related))

        assertEquals(CARDS_PER_ROW, row.size)
        row.chunked(2).forEachIndexed { i, pair ->
            assertEquals("pair $i of $row", setOf("m${i + 1}", "r${i + 1}"), pair.toSet())
        }
    }
}
