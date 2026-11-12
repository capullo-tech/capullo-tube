package tech.capullo.tubecapullo.station

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.capullo.tubecapullo.data.history.LikedAlbums
import tech.capullo.tubecapullo.data.history.LikedTracks
import tech.capullo.tubecapullo.data.history.StationSnapshots
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.settings.LikeMode
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YouTubeUrls
import tech.capullo.tubecapullo.data.youtube.YtChapter
import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtStream
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.data.youtube.YtVideoList
import kotlin.random.Random

/**
 * Home v2 in the station (PLAN "Home v2 and albums"; docs/probes/home-v2-criteria.md sections 1
 * and 4): stations stored in Room and brought back exactly (Continue) or from before an app
 * restart (Previous), chapter-driven Next and Previous in an album, the album's roll-over into a
 * radio and the roll chip, and the album heart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationHomeV2Test {

    private fun video(id: String, seconds: Long = 240) = YtVideo(
        videoId = id.padEnd(11, 'x').take(11),
        title = "t-$id",
        uploader = "u",
        durationSeconds = seconds,
        thumbnailUrl = null,
    )

    private val list = (1..8).map { video("LIST$it") }
    private val playlist = PlaySource.Playlist(YouTubeUrls.playlistUrl("PLtestlist000001"), "Trip")
    private val chapters = listOf(
        YtChapter("Intro", 0),
        YtChapter("Side A", 60_000),
        YtChapter("Side B", 120_000),
    )
    private val albumVideo = video("ALBUM", seconds = 45 * 60L)
    private val album = PlaySource.Album(albumVideo, chapters)

    private val likeMode = MutableStateFlow(LikeMode.BLEND)
    private val likedTracks = mockk<LikedTracks>(relaxed = true)
    private val likedAlbums = mockk<LikedAlbums>(relaxed = true)

    /** Room's station_snapshot table, through the real codec: what a restart reads back. */
    private val rows = HashMap<Long, Pair<Long, String>>()
    private val store = mockk<StationSnapshots>().also { store ->
        coEvery { store.save(any(), any()) } answers {
            val snapshot = firstArg<StationSnapshot>()
            rows[snapshot.id] = snapshot.savedAtEpochMs to StationSnapshotCodec.encode(snapshot)
            val keep = secondArg<Int>()
            rows.entries.sortedByDescending { it.value.first }.drop(keep).forEach { rows.remove(it.key) }
        }
        coEvery { store.all() } answers {
            rows.values.sortedByDescending { it.first }.mapNotNull { StationSnapshotCodec.decode(it.second) }
        }
        coEvery { store.byId(any()) } answers {
            rows[firstArg<Long>()]?.second?.let(StationSnapshotCodec::decode)
        }
    }

    private fun extractor(): YouTubeExtractorClient {
        val extractor = mockk<YouTubeExtractorClient>()
        coEvery { extractor.playlistInfo(any(), any()) } returns YtVideoList("Road trip", list)
        coEvery { extractor.mix(any(), any()) } answers {
            val seed = firstArg<String>()
            listOf(video(seed)) + (1..12).map { video("m$it-$seed".take(11)) }
        }
        coEvery { extractor.relatedStreams(any()) } returns YtRelated(-1, emptyList())
        coEvery { extractor.resolveAudioStream(any(), any()) } answers {
            YtStream("https://example/${firstArg<String>()}", "audio/mp4", 128, null, null, 140, true)
        }
        return extractor
    }

    private fun TestScope.engine(): StationEngine {
        val settings = mockk<AppSettings> {
            every { this@mockk.likeMode } returns this@StationHomeV2Test.likeMode
            every { songsPerLike } returns MutableStateFlow(AppSettings.DEFAULT_SONGS_PER_LIKE)
        }
        val engine = StationEngine(
            extractor(),
            this,
            mockk(relaxed = true),
            likedTracks,
            Random(7),
            settings,
            likedAlbums,
            store,
        )
        advanceUntilIdle() // likes, albums and stored stations hydrate
        return engine
    }

    /** A station change: [source] replaces the station and its first track plays. */
    private fun TestScope.change(engine: StationEngine, source: PlaySource) {
        engine.start(source)
        advanceUntilIdle()
        engine.onQueueAdvanced(0)
        advanceUntilIdle()
    }

    /** [count] Nexts from the playing track, as the loop makes them. */
    private fun TestScope.next(engine: StationEngine, count: Int): List<String> {
        val played = mutableListOf<String>()
        repeat(count) {
            val at = engine.queue().currentIndex + 1
            played += engine.queue().idAt(at) ?: return played
            engine.onQueueAdvanced(at)
            advanceUntilIdle()
        }
        return played
    }

    private fun StationEngine.currentId() = state.value.current?.videoId

    private fun StationEngine.queuedIds() = state.value.queued.map { it.video.videoId }

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
    }

    @After fun tearDown() = unmockkStatic(Log::class)

    // --- Snapshots: Continue and Previous ---

    @Test
    fun `a left list resumes exactly - song, playhead, queue, list place, shuffle and repeat`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        change(engine, playlist)
        engine.cycleRepeat() // ALL
        engine.setShuffle(true)
        next(engine, 2)
        val left = engine.state.value
        val listId = left.stationId
        engine.onProgress(left.current!!.videoId, 83_000, 240_000)
        val rest = engine.queuedIds()

        change(engine, PlaySource.SongRadio(video("ELSEWHERE"))) // a station change saves it
        engine.resume(listId)
        advanceUntilIdle()

        val back = engine.state.value
        assertEquals(left.source, back.source)
        assertEquals(listId, back.stationId)
        assertEquals(left.current, back.current)
        assertEquals(rest, engine.queuedIds())
        assertTrue(back.shuffle)
        assertEquals(RepeatMode.ALL, back.repeat)
        engine.onQueueAdvanced(0)
        assertEquals("the playhead it was left at", 83_000L, engine.startPositionFor(0))
        assertNull("once", engine.startPositionFor(0))
        // The list goes on from its place, in its round.
        assertEquals(rest, next(engine, rest.size))
    }

    @Test
    fun `a station played before an app restart resumes from Room exactly`() = runTest(StandardTestDispatcher()) {
        val before = engine()
        change(before, playlist)
        next(before, 3)
        val left = before.state.value
        before.onProgress(left.current!!.videoId, StationEngine.SNAPSHOT_SAVE_MS + 5_000, 240_000)
        advanceUntilIdle() // the periodic write lands

        val after = engine() // the process died; a new engine reads Room
        after.resume(left.stationId)
        advanceUntilIdle()
        after.onQueueAdvanced(0)

        assertEquals(left.source, after.state.value.source)
        assertEquals(left.current, after.state.value.current)
        assertEquals(left.queued, after.state.value.queued)
        assertEquals(StationEngine.SNAPSHOT_SAVE_MS + 5_000, after.startPositionFor(0))
    }

    @Test
    fun `previous after an app restart brings back the station that played before it`() = runTest(StandardTestDispatcher()) {
        val before = engine()
        change(before, PlaySource.SongRadio(video("FIRST")))
        change(before, playlist)
        next(before, 2)
        val left = before.state.value

        val after = engine()
        change(after, PlaySource.SongRadio(video("AFTER")))
        val at = after.previous(positionMs = 0)

        assertEquals(0, at)
        after.onQueueAdvanced(0)
        assertEquals(left.source, after.state.value.source)
        assertEquals(left.current, after.state.value.current)
        assertEquals(left.queued, after.state.value.queued)
        assertNull("Previous plays it from the start", after.startPositionFor(0))
        assertEquals(PlaySource.SongRadio(video("FIRST")), restoreNext(after))
    }

    /** Previous on a station's first song once more; the source it restores. */
    private fun TestScope.restoreNext(engine: StationEngine): PlaySource? {
        val at = engine.previous(positionMs = 0) ?: return null
        engine.onQueueAdvanced(at)
        advanceUntilIdle()
        // The restored station's own history comes first: walk back to its first song.
        return if (at == 0) engine.state.value.source else restoreNext(engine)
    }

    // --- Albums ---

    @Test
    fun `next and previous move through the chapters, with the 3 s rule inside a chapter`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        change(engine, album)
        assertEquals(AlbumProgress(chapters, 0), engine.state.value.album)

        assertEquals(60_000L, engine.nextChapter(10_000))
        engine.onProgress(albumVideo.videoId, 60_500, 45 * 60_000L)
        assertEquals(1, engine.state.value.album?.index)
        val restart = StationEngine.PREVIOUS_RESTART_MS
        assertEquals("past 3 s: restart the chapter", 60_000L, engine.previousChapter(60_000 + restart + 1))
        assertEquals("at 3 s: the chapter before", 0L, engine.previousChapter(60_000 + restart))
        assertEquals("first chapter, past 3 s: restart it", 0L, engine.previousChapter(restart + 1))
        assertNull("first chapter, under 3 s: Previous acts on the song", engine.previousChapter(restart))
        assertNull("last chapter: Next ends the album", engine.nextChapter(130_000))
    }

    @Test
    fun `up next lists the chapters still to come, then the radio after the album`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        change(engine, album)
        engine.onProgress(albumVideo.videoId, 61_000, 45 * 60_000L)

        val state = engine.state.value
        assertEquals(1, state.album?.index)
        assertTrue("the radio from the album follows it", state.queued.isNotEmpty())
        assertTrue(state.queued.all { it.why.fromVideoId == albumVideo.videoId })
    }

    @Test
    fun `an album without chapters plays as one track`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        change(engine, PlaySource.Album(albumVideo, emptyList()))

        assertNull(engine.state.value.album)
        assertNull(engine.nextChapter(10_000))
        assertNull(engine.previousChapter(10_000))
    }

    @Test
    fun `the album's end rolls into a radio from it, and the chip shows as the radio starts`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        val chips = mutableListOf<RollOver>()
        backgroundScope.launch { engine.rollOvers.toList(chips) }
        change(engine, album)
        val id = engine.state.value.stationId
        runCurrent()
        assertTrue(chips.isEmpty())

        next(engine, 1) // the album ended

        val state = engine.state.value
        assertEquals(PlaySource.SongRadio(albumVideo), state.source)
        assertEquals("the same station going on", id, state.stationId)
        assertNull(state.album)
        runCurrent()
        assertEquals(listOf(RollOver(album)), chips)
    }

    @Test
    fun `a playlist's roll chip waits for the first radio track`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        val chips = mutableListOf<RollOver>()
        backgroundScope.launch { engine.rollOvers.toList(chips) }
        change(engine, playlist)
        next(engine, list.size - 1) // the last list track plays: the title is Radio already
        runCurrent()
        assertTrue(engine.state.value.source is PlaySource.SongRadio)
        assertTrue("not ended yet", chips.isEmpty())

        next(engine, 1)
        runCurrent()

        assertEquals(listOf(RollOver(playlist.copy(title = "Road trip"))), chips)
    }

    @Test
    fun `the heart in album mode saves or removes the album and never touches the queue`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        change(engine, album)
        val queued = engine.queuedIds()

        engine.toggleHeart(albumVideo)
        advanceUntilIdle()

        assertTrue(albumVideo.videoId in engine.likedAlbumIds.value)
        assertFalse("not a liked song", albumVideo.videoId in engine.likedIds.value)
        assertTrue(engine.state.value.albumMode)
        assertEquals("Blend in does not apply", queued, engine.queuedIds())
        verify { likedAlbums.set(albumVideo, true) }
        verify(exactly = 0) { likedTracks.set(any(), any()) }

        engine.toggleHeart(albumVideo)
        assertFalse(albumVideo.videoId in engine.likedAlbumIds.value)
        verify { likedAlbums.set(albumVideo, false) }
    }

    @Test
    fun `a liked album never joins Liked or Liked radio`() = runTest(StandardTestDispatcher()) {
        val song = video("SONG")
        coEvery { likedTracks.all() } returns listOf(song)
        val engine = engine()
        change(engine, album)
        engine.toggleHeart(albumVideo)

        change(engine, PlaySource.Liked(shuffle = false))
        assertEquals(song, engine.state.value.current)
        assertTrue(engine.queuedIds().none { it == albumVideo.videoId })
    }
}
