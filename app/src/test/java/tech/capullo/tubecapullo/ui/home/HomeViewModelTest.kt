package tech.capullo.tubecapullo.ui.home

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import tech.capullo.tubecapullo.data.history.LikedAlbums
import tech.capullo.tubecapullo.data.history.LikedEntry
import tech.capullo.tubecapullo.data.history.LikedTracks
import tech.capullo.tubecapullo.data.history.PlayHistory
import tech.capullo.tubecapullo.data.history.PlayStat
import tech.capullo.tubecapullo.data.history.StationSnapshots
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.Provenance
import tech.capullo.tubecapullo.station.RepeatMode
import tech.capullo.tubecapullo.station.SnapshotSlot
import tech.capullo.tubecapullo.station.StationEngine
import tech.capullo.tubecapullo.station.StationSnapshot
import tech.capullo.tubecapullo.station.StationState
import tech.capullo.tubecapullo.station.Why
import java.util.Locale
import kotlin.random.Random

/**
 * Which Home rows reload on which change. The extractor, the stores, the player and the settings
 * are fakes with live flows, so a test changes likes, plays, stations or settings the way the
 * app does and counts the fetches each change costs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val day = 24 * 60 * 60 * 1000L
    private val likes = MutableStateFlow(listOf(video("like1")))
    private val albums = MutableStateFlow(emptyList<LikedEntry>())
    private val chartRegions = MutableStateFlow(listOf("NI"))
    private val moreLikeRows = MutableStateFlow(2)
    private val fullAlbums = MutableStateFlow(true)
    private val continueCards = MutableStateFlow(AppSettings.MAX_CONTINUE_CARDS)
    private val stations = MutableStateFlow(emptyList<StationSnapshot>())
    private val station = MutableStateFlow(StationState())
    private val extractor = mockk<YouTubeExtractorClient>()
    private val likedTracks = mockk<LikedTracks>()
    private val likedAlbums = mockk<LikedAlbums>()
    private val playHistory = mockk<PlayHistory>()
    private val snapshots = mockk<StationSnapshots>()

    /** One song played often, last 40 days ago: a Listen again pick by the plays rule. */
    private val pool = MutableStateFlow(listOf(oldPlay("played1")))
    private val playerState = MutableStateFlow(PlaybackCoordinator.State())

    private fun video(id: String) = YtVideo(id, "Title $id", "Uploader", 200, null)

    private fun oldPlay(id: String) = PlayStat(video(id), LISTEN_AGAIN_MIN_PLAYS, System.currentTimeMillis() - 40 * day)

    private fun stored(id: Long, left: String): StationSnapshot = StationSnapshot(
        id = id,
        savedAtEpochMs = id,
        source = PlaySource.SongRadio(video("seed$id")),
        slots = listOf(SnapshotSlot(video(left), Why(Provenance.SEED, null, null), false, false)),
        left = 0,
        positionMs = 0,
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
        round = emptyList(),
        roundPos = -1,
        nextRound = null,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        coEvery { extractor.trendingMusic(any(), any()) } answers {
            listOf(video("chart-${firstArg<String?>()}"))
        }
        coEvery { extractor.relatedStreams(any()) } answers {
            YtRelated(seedViews = -1, videos = emptyList())
        }
        coEvery { extractor.mix(any(), any()) } answers {
            listOf(video("mix-${firstArg<String>()}"))
        }
        coEvery { extractor.search(any(), any()) } returns emptyList()
        every { extractor.supportedRegions() } returns listOf("JP", "KR", "NI", "NO", "US")
        coEvery { likedTracks.all() } answers { likes.value }
        every { likedTracks.observe() } returns likes
        // Liked just now: too new for Listen again, so only the plays rule fills it here.
        every { likedTracks.observeEntries() } returns
            likes.map { list -> list.map { LikedEntry(it, System.currentTimeMillis()) } }
        coEvery { likedAlbums.all() } answers { albums.value.map { it.video } }
        every { likedAlbums.observeEntries() } returns albums
        every { playHistory.observeListenAgainPool(any()) } returns pool
        every { snapshots.observe() } returns stations
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun viewModel(): HomeViewModel {
        val settings = mockk<AppSettings>()
        every { settings.chartRegions } returns chartRegions
        every { settings.moreLikeRows } returns moreLikeRows
        every { settings.fullAlbums } returns fullAlbums
        every { settings.continueCards } returns continueCards
        val player = mockk<PlaybackCoordinator>()
        every { player.events } returns MutableSharedFlow()
        every { player.state } returns playerState
        val engine = mockk<StationEngine>()
        every { engine.state } returns station
        return HomeViewModel(
            settings,
            extractor,
            player,
            engine,
            playHistory,
            likedTracks,
            likedAlbums,
            snapshots,
            Random(1),
        )
    }

    private fun HomeViewModel.keys() = uiState.value.sections.map { it.key }

    @Test
    fun `a burst of likes reloads the like rows once, sharing one fetch, and leaves Trending alone`() = runTest(dispatcher) {
        val home = viewModel()
        advanceUntilIdle()
        assertEquals(listOf("listen-again", "liked-like1", "trending-NI"), home.keys())
        val trending = home.uiState.value.trending

        likes.value = listOf(video("like2")) + likes.value
        advanceTimeBy(300) // inside the debounce window
        likes.value = listOf(video("like3")) + likes.value
        advanceUntilIdle()

        assertEquals(
            listOf("listen-again", "liked-like3", "liked-like2", "trending-NI"),
            home.keys(),
        )
        assertSame(trending, home.uiState.value.trending)
        coVerify(exactly = 1) { extractor.trendingMusic(any(), any()) }
        // Two reloads would have fetched like2 twice; the More like and Full albums rows of one
        // reload share its related list.
        coVerify(exactly = 1) { extractor.relatedStreams("like2") }
        coVerify(exactly = 1) { extractor.mix("like2", any()) }
    }

    @Test
    fun `zero More like rows hides the section and fetches nothing for it`() = runTest(dispatcher) {
        moreLikeRows.value = 0
        fullAlbums.value = false
        val home = viewModel()
        advanceUntilIdle()

        assertEquals(listOf("listen-again", "trending-NI"), home.keys())
        coVerify(exactly = 0) { extractor.relatedStreams(any()) }
        coVerify(exactly = 0) { extractor.mix(any(), any()) }

        moreLikeRows.value = 1
        advanceUntilIdle()

        assertEquals(listOf("listen-again", "liked-like1", "trending-NI"), home.keys())
        coVerify(exactly = 1) { extractor.trendingMusic(any(), any()) }
    }

    @Test
    fun `Full albums off fetches nothing for it, and on again the row comes back with its search`() = runTest(dispatcher) {
        moreLikeRows.value = 0
        fullAlbums.value = false
        val album = YtVideo("alb", "Artist - Record (Full Album)", "Artist", 2700, null, uploaderVerified = true)
        coEvery { extractor.search("Uploader full album", any()) } returns listOf(album)
        val home = viewModel()
        advanceUntilIdle()
        coVerify(exactly = 0) { extractor.search(any(), any()) }

        fullAlbums.value = true
        advanceUntilIdle()

        assertEquals(listOf("listen-again", "full-albums", "trending-NI"), home.keys())
        assertEquals(listOf(album), home.uiState.value.fullAlbums)
        coVerify(exactly = 1) { extractor.trendingMusic(any(), any()) }
    }

    private fun album(id: String, title: String, uploader: String = "Fan Channel", verified: Boolean = false, views: Long = 100_000) = YtVideo(id, title, uploader, 2700, null, viewCount = views, uploaderVerified = verified)

    @Test
    fun `a short Full albums row is topped up to FULL_ALBUM_CARDS by the search, one card per album across both`() = runTest(dispatcher) {
        moreLikeRows.value = 0
        // Related: two uploads of one album and a second album, under the minimum.
        coEvery { extractor.relatedStreams("like1") } returns YtRelated(
            seedViews = -1,
            videos = listOf(
                album("r1", "Uploader - First Record (Full Album)", views = 50_000),
                album("r2", "First Record [FULL ALBUM] HD", views = 900_000),
                album("r3", "Second Record (Full Album)"),
            ),
        )
        // Search: the verified upload of the first album, a smaller upload of the second, and
        // more albums than the row has room for.
        coEvery { extractor.search("Uploader full album", any()) } returns listOf(
            album("s1", "First Record (Remastered 2001) - Full Album", "Uploader", verified = true, views = 1_000),
            album("s2", "Uploader - Second Record (Full Album)", "Uploader", views = 20_000),
        ) + (3..FULL_ALBUM_CARDS + 2).map { album("s$it", "Record $it (Full Album)") }

        val home = viewModel()
        advanceUntilIdle()

        // The verified upload takes the first album's place; the second keeps its related upload.
        val expected = listOf("s1", "r3") + (3..FULL_ALBUM_CARDS).map { "s$it" }
        assertEquals(expected, home.uiState.value.fullAlbums.map { it.videoId })
    }

    @Test
    fun `changing the charts refetches only Trending, one row per chart in order`() = runTest(dispatcher) {
        val home = viewModel()
        advanceUntilIdle()

        chartRegions.value = listOf("NO", "US")
        advanceUntilIdle()

        assertEquals(
            listOf("listen-again", "liked-like1", "trending-NO", "trending-US"),
            home.keys(),
        )
        coVerify(exactly = 1) { extractor.relatedStreams(any()) }

        // No chosen chart: one row for the device locale's country, or its closest served
        // neighbour when YouTube Charts does not serve it (Taiwan here), forced like a pick.
        val locale = Locale.getDefault()
        Locale.setDefault(Locale.TAIWAN)
        try {
            chartRegions.value = emptyList()
            advanceUntilIdle()
        } finally {
            Locale.setDefault(locale)
        }

        assertEquals(listOf("listen-again", "liked-like1", "trending-JP"), home.keys())
        coVerify(exactly = 1) { extractor.trendingMusic("JP", any()) }
    }

    @Test
    fun `Continue and Listen again follow stations, plays and the playing track without a fetch`() = runTest(dispatcher) {
        val home = viewModel()
        advanceUntilIdle()
        fun continueIds() = home.uiState.value.continueCards.map { it.stationId }
        fun listenAgain() = home.uiState.value.listenAgain?.cards.orEmpty().map { it.videoId }

        // Two stored stations; the newer one plays, so only the older is a card.
        stations.value = listOf(stored(2, "left2"), stored(1, "played1"))
        station.value = StationState(stationId = 2)
        advanceUntilIdle()
        assertEquals(listOf(1L), continueIds())
        assertEquals("a Continue song leaves Listen again", emptyList<String>(), listenAgain())

        // The card's station is left behind by a new one: its song is still a Continue item.
        station.value = StationState(stationId = 3)
        stations.value = listOf(stored(3, "other"), stored(2, "left2"), stored(1, "played1"))
        advanceUntilIdle()
        assertEquals(listOf(2L, 1L), continueIds())

        // Continue cards off: the song comes back to Listen again, unless it plays.
        continueCards.value = 0
        advanceUntilIdle()
        assertEquals(listOf("played1"), listenAgain())
        playerState.value = PlaybackCoordinator.State(video = video("played1"))
        advanceUntilIdle()
        assertEquals(emptyList<String>(), listenAgain())

        coVerify(exactly = 1) { extractor.trendingMusic(any(), any()) }
    }
}
