package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory

/**
 * The Search screen's filters against live YouTube. Network-gated behind `YT_SMOKE=1`, like the
 * RD-mix probe:
 *
 * ```
 * YT_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*SearchFilterProbeTest*'
 * ```
 *
 * Q1 and Q2 assert that each filter lists rows and that a row's tap target loads. Q3 measures
 * why Channels uses the plain `CHANNELS` filter: how many `MUSIC_ARTISTS` results have a Videos
 * tab. Run 2026-11-11 ("daft punk", en-US): Q1 songs 20 (then `MUSIC_SONGS`; Songs now uses the
 * plain `VIDEOS` search, not re-run since), albums 20, playlists 20, channels 20 rows, none with
 * an item count; Q2 first album 13 tracks, first playlist 10, first channel (the official one)
 * 186 uploads; Q3 0 of 3 `MUSIC_ARTISTS` channels had a Videos tab (all "<Artist> - Topic").
 */
class SearchFilterProbeTest {

    private lateinit var client: YouTubeExtractorClient

    @Before
    fun setUp() {
        assumeTrue("set YT_SMOKE=1 to run the search filter probe", System.getenv("YT_SMOKE") == "1")
        client = probeClient()
    }

    @Test
    fun `q1 every filter lists rows`() = runBlocking {
        val songs = client.search(QUERY)
        println("PROBE q1 songs=${songs.size} first3=${songs.take(3).map { it.title }}")
        assertTrue("Q1 FAIL: ${songs.size} songs", songs.size >= MIN_ROWS)
        songs.forEach { assertTrue("bad id ${it.videoId}", it.videoId.length == 11) }

        CollectionKind.entries.forEach { kind ->
            val rows = client.searchCollections(QUERY, kind)
            println(
                "PROBE q1 kind=$kind rows=${rows.size} " +
                    "withCount=${rows.count { it.itemCount != null }} " +
                    "withArt=${rows.count { it.thumbnailUrl != null }} first=${rows.firstOrNull()}",
            )
            assertTrue("Q1 FAIL: $kind gave ${rows.size} rows", rows.size >= MIN_ROWS)
        }
    }

    @Test
    fun `q2 the first album, playlist and channel load as lists`() = runBlocking {
        listOf(CollectionKind.ALBUMS, CollectionKind.PLAYLISTS).forEach { kind ->
            val first = client.searchCollections(QUERY, kind).first()
            val tracks = client.playlistInfo(first.url).videos
            println("PROBE q2 kind=$kind url=${first.url} tracks=${tracks.size}")
            assertTrue("Q2 FAIL: $kind ${first.url} has no tracks", tracks.isNotEmpty())
        }
        val channel = client.searchCollections(QUERY, CollectionKind.CHANNELS).first()
        val uploads = client.channelUploads(channel.url).videos
        println("PROBE q2 kind=CHANNELS name=${channel.title} uploads=${uploads.size}")
        assertTrue("Q2 FAIL: channel ${channel.url} has no uploads", uploads.isNotEmpty())
    }

    @Test
    fun `q3 music artists with a Videos tab - measurement only`() {
        NewPipe.init(TubeCapulloDownloader(), Localization("en", "US"), ContentCountry("US"))
        val handler = ServiceList.YouTube.searchQHFactory
            .fromQuery(QUERY, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS), "")
        val artists = SearchInfo.getInfo(ServiceList.YouTube, handler).relatedItems
            .filterIsInstance<ChannelInfoItem>()
            .take(ARTISTS_CHECKED)
        val withVideos = artists.count { artist ->
            val channel = ChannelInfo.getInfo(ServiceList.YouTube, artist.url)
            println("PROBE q3 artist=${artist.name} channel=${channel.name} tabs=${channel.tabs.size}")
            channel.tabs.any { ChannelTabs.VIDEOS in it.contentFilters }
        }
        println("PROBE q3 withVideosTab=$withVideos of ${artists.size}")
    }

    private companion object {
        /** A catalogue with albums, an official channel and a Topic channel. */
        const val QUERY = "daft punk"

        /** Q1's floor: one page gave 20 rows for every filter on 2026-11-11. */
        const val MIN_ROWS = 5

        /** Channel page fetches in Q3, one request each. */
        const val ARTISTS_CHECKED = 3
    }
}
