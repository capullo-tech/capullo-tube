package tech.capullo.tubecapullo.station

import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.capullo.tubecapullo.data.history.LikedTracks
import tech.capullo.tubecapullo.data.history.PlayHistory
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.settings.LikeMode
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YouTubeUrls
import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtStream
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.data.youtube.YtVideoList
import kotlin.random.Random

private const val RANDOM_SEED = 7

private const val PLAYLIST_ID = "PLtestlist000001"

private fun silenceStationLog() {
    mockkStatic(Log::class)
    every { Log.i(any(), any()) } returns 0
}

private fun fakeSettings(
    mode: MutableStateFlow<LikeMode> = MutableStateFlow(LikeMode.BLEND),
    songs: MutableStateFlow<Int> = MutableStateFlow(AppSettings.DEFAULT_SONGS_PER_LIKE),
): AppSettings = mockk {
    every { likeMode } returns mode
    every { songsPerLike } returns songs
}

private fun stream(id: String) = YtStream(
    url = "https://example/$id",
    mimeType = "audio/mp4",
    bitrateKbps = 128,
    contentLength = null,
    expiresAtEpochSeconds = null,
    itag = 140,
    isM4a = true,
)

private fun StationEngine.advanceThrough(count: Int): List<String> {
    val played = mutableListOf<String>()
    var i = 0
    while (played.size < count) {
        val id = queue().idAt(i) ?: break
        played += id
        onQueueAdvanced(i)
        i++
    }
    return played
}

private fun TestScope.walkFrom(engine: StationEngine, from: Int, count: Int): List<String> {
    val played = mutableListOf<String>()
    for (i in from until from + count) {
        played += engine.queue().idAt(i) ?: break
        engine.onQueueAdvanced(i)
        advanceUntilIdle()
    }
    return played
}

private fun video(id: String, seconds: Long = 240) = YtVideo(
    videoId = id.padEnd(11, 'x').take(11),
    title = "t-$id",
    uploader = "u",
    durationSeconds = seconds,
    thumbnailUrl = null,
)

private fun fakeMix(seed: String, size: Int = 20) = listOf(video(seed)) + (1..size).map { video("m$it-$seed".take(11)) }

private fun fakeRelated(seed: String) = (1..12).map { video("r$it-$seed".take(11)).copy(uploaderVerified = true) }

private fun relatedHead(seed: String, n: Int = fakeRelated(seed).size) = fakeRelated(seed).take(n).map { it.videoId }

private fun fakeExtractor(
    mixSeeds: MutableList<String> = mutableListOf(),
    mixSize: Int = 20,
    related: (String) -> List<YtVideo> = { emptyList() },
): YouTubeExtractorClient {
    val extractor = mockk<YouTubeExtractorClient>()
    coEvery { extractor.mix(any(), any()) } answers {
        val seed = firstArg<String>()
        mixSeeds += seed
        fakeMix(seed, mixSize)
    }
    coEvery { extractor.relatedStreams(any()) } answers {
        YtRelated(seedViews = 1_000_000, videos = related(firstArg()))
    }
    coEvery { extractor.resolveAudioStream(any(), any()) } answers { stream(firstArg()) }
    return extractor
}

private fun StationEngine.queuedIds() = state.value.queued.map { it.video.videoId }

@OptIn(ExperimentalCoroutinesApi::class)
class StationEngineTest {
    private val playHistory = mockk<PlayHistory>(relaxed = true)
    private val likedTracks = mockk<LikedTracks>(relaxed = true)

    private fun TestScope.engine(extractor: YouTubeExtractorClient = fakeExtractor()) = StationEngine(
        extractor,
        this,
        playHistory,
        likedTracks,
        Random(RANDOM_SEED),
        fakeSettings(),
        mockk(relaxed = true),
        mockk(relaxed = true),
    )

    @Before fun setUp() = silenceStationLog()

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `a song radio is the seed, then its Mix in YouTube's order`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        val seed = video("SEED")
        engine.start(PlaySource.SongRadio(seed))
        advanceUntilIdle()

        val state = engine.state.value
        assertEquals(seed, state.current)
        assertEquals(fakeMix(seed.videoId).drop(1).map { it.videoId }, engine.queuedIds())
        val why = Why(Provenance.MIX, seed.videoId, seed.title)
        assertTrue(state.queued.all { it.why == why })
    }

    @Test
    fun `a search radio plays the first result, the other results, then the first one's Mix`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        val results = listOf(video("RES1"), video("RES2"), video("RES3"))
        engine.start(PlaySource.SearchRadio("q", results))
        advanceUntilIdle()

        val queued = engine.state.value.queued
        assertEquals(results[0], engine.state.value.current)
        assertEquals(results.drop(1).map { it.videoId }, engine.queuedIds().take(2))
        assertTrue(queued.take(2).all { it.why.provenance == Provenance.SEARCH })
        assertEquals(
            fakeMix(results[0].videoId).drop(1).map { it.videoId },
            engine.queuedIds().drop(2),
        )
    }

    @Test
    fun `below CHAIN_BELOW the Mix of the last song played is appended`() = runTest(StandardTestDispatcher()) {
        val mixSeeds = mutableListOf<String>()
        val mixSize = StationEngine.CHAIN_BELOW + 3
        val engine = engine(fakeExtractor(mixSeeds, mixSize))
        val seed = video("SEED")
        engine.start(PlaySource.SongRadio(seed))
        advanceUntilIdle()
        assertEquals(listOf(seed.videoId), mixSeeds)

        val chainAt = mixSize - StationEngine.CHAIN_BELOW + 1
        engine.advanceThrough(chainAt)
        advanceUntilIdle()
        assertEquals("chained before the queue ran low", listOf(seed.videoId), mixSeeds)

        engine.onQueueAdvanced(chainAt)
        advanceUntilIdle()
        val last = engine.videoAt(chainAt)!!
        assertEquals(listOf(seed.videoId, last.videoId), mixSeeds)
        val queued = engine.state.value.queued
        assertEquals(
            "the current Mix plays out first",
            StationEngine.CHAIN_BELOW - 1,
            queued.takeWhile { it.why.fromVideoId == seed.videoId }.size,
        )
        val chained = queued.drop(StationEngine.CHAIN_BELOW - 1)
        assertEquals(
            fakeMix(last.videoId, mixSize).drop(1).map { it.videoId },
            chained.map { it.video.videoId },
        )
        assertTrue(chained.all { it.why == Why(Provenance.MIX, last.videoId, last.title) })
    }

    @Test
    fun `when one list is empty for a seed the other fills every place`() = runTest(StandardTestDispatcher()) {
        val extractor = fakeExtractor(related = ::fakeRelated)
        coEvery { extractor.mix("NOMIXxxxxxx", any()) } returns emptyList()
        coEvery { extractor.relatedStreams("NORELxxxxxx") } returns
            YtRelated(seedViews = 1_000_000, videos = emptyList())
        val engine = engine(extractor)

        val noMix = video("NOMIX")
        engine.start(PlaySource.SongRadio(noMix))
        advanceUntilIdle()
        assertEquals(relatedHead(noMix.videoId), engine.queuedIds())
        val why = Why(Provenance.RELATED, noMix.videoId, noMix.title)
        assertTrue(engine.state.value.queued.all { it.why == why })

        val noRelated = video("NOREL")
        engine.start(PlaySource.SongRadio(noRelated))
        advanceUntilIdle()
        assertEquals(fakeMix(noRelated.videoId).drop(1).map { it.videoId }, engine.queuedIds())
    }

    @Test
    fun `no id plays twice in a session, across chains and new radios`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        engine.start(PlaySource.SongRadio(video("SEEDA")))
        advanceUntilIdle()
        val first = mutableListOf<String>()
        repeat(60) { i ->
            first += engine.queue().idAt(i) ?: return@repeat
            engine.onQueueAdvanced(i)
            advanceUntilIdle()
        }
        assertEquals("the station ran dry", 60, first.size)
        assertEquals("a played id returned: $first", first.size, first.distinct().size)

        val extractor = fakeExtractor()
        val replayed = first.drop(1).map { video(it) }
        coEvery { extractor.mix("SEEDBxxxxxx", any()) } returns listOf(video("SEEDB")) + replayed
        val again = engine(extractor)
        again.start(PlaySource.SongRadio(video("SEEDA")))
        advanceUntilIdle()
        val played = again.advanceThrough(3)
        again.start(PlaySource.SongRadio(video("SEEDB")))
        advanceUntilIdle()
        val queued = again.queuedIds()
        assertTrue("played tracks queued again: $queued", queued.none { it in played })
    }

    @Test
    fun `durations outside the window never enter the queue`() = runTest(StandardTestDispatcher()) {
        val extractor = fakeExtractor()
        coEvery { extractor.mix(any(), any()) } returns listOf(
            video("short", seconds = StationEngine.MIN_TRACK_SECONDS - 1),
            video("low", seconds = StationEngine.MIN_TRACK_SECONDS),
            video("marathon", seconds = StationEngine.MAX_TRACK_SECONDS + 1),
            video("high", seconds = StationEngine.MAX_TRACK_SECONDS),
        )
        val engine = engine(extractor)

        engine.start(PlaySource.SongRadio(video("SEED")))
        advanceUntilIdle()

        assertEquals(listOf(video("low").videoId, video("high").videoId), engine.queuedIds())
    }

    @Test
    fun `radio shuffle keeps the current track and pins, and shuffles the rest of the Mix`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        engine.start(PlaySource.SongRadio(video("SEED")))
        advanceUntilIdle()
        engine.advanceThrough(2)
        val now = engine.state.value.current
        val pin = video("PINNED")
        engine.playNext(pin)
        val before = engine.queuedIds()

        engine.setShuffle(true)

        val after = engine.queuedIds()
        assertTrue(engine.state.value.shuffle)
        assertEquals(now, engine.state.value.current)
        assertEquals(pin.videoId, after.first())
        assertEquals(before.sorted(), after.sorted())
        assertNotEquals("the rest kept Mix order", before, after)
    }

    @Test
    fun `a Mix chained while shuffle is on comes shuffled`() = runTest(StandardTestDispatcher()) {
        val mixSize = StationEngine.CHAIN_BELOW + 10
        val engine = engine(fakeExtractor(mixSize = mixSize))
        engine.start(PlaySource.SongRadio(video("SEED")))
        advanceUntilIdle()
        engine.setShuffle(true)

        val chainAt = mixSize - StationEngine.CHAIN_BELOW + 1
        engine.advanceThrough(chainAt + 1)
        advanceUntilIdle()

        val last = engine.videoAt(chainAt)!!
        val chained = engine.state.value.queued
            .filter { it.why.fromVideoId == last.videoId }
            .map { it.video.videoId }
        val mixOrder = fakeMix(last.videoId, mixSize).drop(1).map { it.videoId }
        assertEquals(mixOrder.sorted(), chained.sorted())
        assertNotEquals("the chained Mix kept its order", mixOrder, chained)
    }

    @Test
    fun `a jump in a radio plays the tapped track, then the queue goes on`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        engine.start(PlaySource.SongRadio(video("SEED")))
        advanceUntilIdle()
        engine.advanceThrough(1)
        val before = engine.queuedIds()
        val tapped = engine.state.value.queued.last().video

        val at = engine.jumpTo(tapped)!!
        assertEquals(tapped.videoId, engine.queue().idAt(at))
        engine.onQueueAdvanced(at)

        assertEquals(tapped, engine.state.value.current)
        assertEquals(before - tapped.videoId, engine.queuedIds().take(before.size - 1))
    }

    @Test
    fun `a play counts in history only past the threshold, and once`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        val seed = video("seed0000000")
        val durationMs = seed.durationSeconds * 1000
        engine.start(PlaySource.SongRadio(seed))
        advanceUntilIdle()
        engine.advanceThrough(1)

        engine.onProgress(seed.videoId, StationEngine.PLAY_COUNT_MIN_MS - 1, durationMs)
        advanceUntilIdle()
        coVerify(exactly = 0) { playHistory.record(any()) }

        engine.onProgress(seed.videoId, StationEngine.PLAY_COUNT_MIN_MS, durationMs)
        engine.onProgress(seed.videoId, StationEngine.PLAY_COUNT_MIN_MS * 2, durationMs)
        engine.onQueueAdvanced(0)
        engine.onProgress(seed.videoId, StationEngine.PLAY_COUNT_MIN_MS * 3, durationMs)
        advanceUntilIdle()
        coVerify(exactly = 1) { playHistory.record(match { it.videoId == seed.videoId }) }
    }

    @Test
    fun `a short track counts at its fraction, and only for the playing id`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        val seed = video("short000000", seconds = 40)
        val durationMs = seed.durationSeconds * 1000
        val threshold = (durationMs * StationEngine.PLAY_COUNT_FRACTION).toLong()
        assertTrue("precondition: fraction first", threshold < StationEngine.PLAY_COUNT_MIN_MS)
        engine.start(PlaySource.SongRadio(seed))
        advanceUntilIdle()
        engine.advanceThrough(1)

        engine.onProgress("otherxxxxxx", threshold * 2, durationMs)
        engine.onProgress(seed.videoId, threshold - 1, durationMs)
        advanceUntilIdle()
        coVerify(exactly = 0) { playHistory.record(any()) }

        engine.onProgress(seed.videoId, threshold, durationMs)
        advanceUntilIdle()
        coVerify(exactly = 1) { playHistory.record(match { it.videoId == seed.videoId }) }
    }

    @Test
    fun `liking persists through the liked store`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        val seed = video("seed0000000")
        engine.start(PlaySource.SongRadio(seed))
        advanceUntilIdle()
        engine.advanceThrough(1)

        engine.toggleLike(seed)
        verify { likedTracks.set(seed, true) }

        engine.toggleLike(seed)
        verify { likedTracks.set(seed, false) }
    }

    @Test
    fun `likes hydrate from the store at startup`() = runTest(StandardTestDispatcher()) {
        coEvery { likedTracks.all() } returns listOf(video("liked000001"))
        val engine = engine()
        advanceUntilIdle()

        assertTrue(
            "hydrated like missing: ${engine.likedIds.value}",
            "liked000001" in engine.likedIds.value,
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StationLikeModeTest {
    private val likedTracks = mockk<LikedTracks>(relaxed = true)
    private val likeMode = MutableStateFlow(LikeMode.BLEND)
    private val songs = MutableStateFlow(AppSettings.DEFAULT_SONGS_PER_LIKE)
    private val mixSeeds = mutableListOf<String>()
    private val extractor = fakeExtractor(mixSeeds)

    private fun TestScope.playing(): StationEngine {
        val engine = StationEngine(
            extractor,
            this,
            mockk(relaxed = true),
            likedTracks,
            Random(RANDOM_SEED),
            fakeSettings(likeMode, songs),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        engine.start(PlaySource.SongRadio(video("SEED")))
        advanceUntilIdle()
        engine.advanceThrough(2)
        advanceUntilIdle()
        return engine
    }

    private fun TestScope.effectsOf(engine: StationEngine): List<LikeEffect> {
        val effects = mutableListOf<LikeEffect>()
        backgroundScope.launch { engine.likeEffects.toList(effects) }
        runCurrent()
        return effects
    }

    private fun mixHead(liked: YtVideo, n: Int) = fakeMix(liked.videoId).drop(1).take(n).map { it.videoId }

    @Before fun setUp() = silenceStationLog()

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `blend puts N of the liked song's Mix after the current one, then the queue resumes`() = runTest(StandardTestDispatcher()) {
        for (n in listOf(AppSettings.DEFAULT_SONGS_PER_LIKE, AppSettings.MAX_SONGS_PER_LIKE)) {
            songs.value = n
            val engine = playing()
            val effects = effectsOf(engine)
            val before = engine.queuedIds()
            val liked = engine.state.value.current!!

            engine.toggleLike(liked)
            advanceUntilIdle()

            val after = engine.state.value.queued
            val blended = after.take(n)
            assertEquals("N=$n", mixHead(liked, n), blended.map { it.video.videoId })
            assertTrue(blended.all { it.why == Why(Provenance.MIX, liked.videoId, liked.title) })
            assertEquals("N=$n", before, after.drop(n).map { it.video.videoId })
            runCurrent()
            assertEquals(listOf(LikeEffect.Blended(liked, n)), effects)
        }
    }

    @Test
    fun `with no songs added per like a blend like only saves`() = runTest(StandardTestDispatcher()) {
        songs.value = 0
        val engine = playing()
        val effects = effectsOf(engine)
        val before = engine.queuedIds()
        val liked = engine.state.value.current!!

        engine.toggleLike(liked)
        advanceUntilIdle()

        assertEquals(before, engine.queuedIds())
        assertFalse("fetched the liked song's Mix", liked.videoId in mixSeeds)
        assertTrue(liked.videoId in engine.likedIds.value)
        runCurrent()
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `blend moves an upcoming track up instead of queueing it twice`() = runTest(StandardTestDispatcher()) {
        val n = songs.value
        val engine = playing()
        val liked = engine.state.value.current!!
        val upcoming = engine.state.value.queued[5].video
        coEvery { extractor.mix(liked.videoId, any()) } returns
            listOf(liked, upcoming) + fakeMix(liked.videoId).drop(1)
        val before = engine.queuedIds()

        engine.toggleLike(liked)
        advanceUntilIdle()

        val after = engine.queuedIds()
        val head = listOf(upcoming.videoId) + mixHead(liked, n - 1)
        assertEquals(head, after.take(n))
        assertEquals(before - upcoming.videoId, after.drop(n))
    }

    @Test
    fun `a second blend interleaves with what the first one left`() = runTest(StandardTestDispatcher()) {
        songs.value = 4
        val engine = playing()
        val first = engine.state.value.current!!
        engine.toggleLike(first)
        advanceUntilIdle()
        val firstMix = mixHead(first, 4)
        engine.onQueueAdvanced(2)
        val second = engine.state.value.current!!
        assertEquals(firstMix[0], second.videoId)

        engine.toggleLike(second)
        advanceUntilIdle()

        val secondMix = mixHead(second, 4)
        assertEquals(
            listOf(
                secondMix[0],
                firstMix[1],
                secondMix[1],
                firstMix[2],
                secondMix[2],
                firstMix[3],
                secondMix[3],
            ),
            engine.queuedIds().take(7),
        )
    }

    @Test
    fun `save only saves the like and leaves the queue as it was`() = runTest(StandardTestDispatcher()) {
        likeMode.value = LikeMode.SAVE_ONLY
        val engine = playing()
        val effects = effectsOf(engine)
        val before = engine.queuedIds()
        val liked = engine.state.value.current!!

        engine.toggleLike(liked)
        advanceUntilIdle()

        assertEquals(before, engine.queuedIds())
        assertFalse("fetched the liked song's Mix", liked.videoId in mixSeeds)
        assertTrue(liked.videoId in engine.likedIds.value)
        runCurrent()
        assertTrue(effects.isEmpty())
    }

    @Test
    fun `switch now makes the liked song's Mix the queue, after the pins, and the current track plays on`() = runTest(StandardTestDispatcher()) {
        likeMode.value = LikeMode.SWITCH_NOW
        val engine = playing()
        val effects = effectsOf(engine)
        val pin = video("PINNED")
        engine.playNext(pin)
        val liked = engine.state.value.current!!

        engine.toggleLike(liked)
        advanceUntilIdle()

        assertEquals(liked, engine.state.value.current)
        val mix = fakeMix(liked.videoId).drop(1).map { it.videoId }
        assertEquals(listOf(pin.videoId) + mix, engine.queuedIds())
        runCurrent()
        assertEquals(listOf(LikeEffect.Switched(liked, mix.size)), effects)
    }

    @Test
    fun `an unlike in a radio changes nothing`() = runTest(StandardTestDispatcher()) {
        likeMode.value = LikeMode.SWITCH_NOW
        val engine = playing()
        val liked = engine.state.value.current!!
        engine.toggleLike(liked)
        advanceUntilIdle()
        val before = engine.queuedIds()
        mixSeeds.clear()

        engine.toggleLike(liked)
        advanceUntilIdle()

        assertEquals(before, engine.queuedIds())
        assertTrue(mixSeeds.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StationLikedRadioTest {
    private val likedTracks = mockk<LikedTracks>(relaxed = true)
    private val likeMode = MutableStateFlow(LikeMode.BLEND)
    private val songs = MutableStateFlow(AppSettings.DEFAULT_SONGS_PER_LIKE)
    private val mixSeeds = mutableListOf<String>()

    private fun TestScope.engine(likes: List<YtVideo>): StationEngine {
        coEvery { likedTracks.all() } returns likes
        val engine = StationEngine(
            fakeExtractor(mixSeeds),
            this,
            mockk(relaxed = true),
            likedTracks,
            Random(RANDOM_SEED),
            fakeSettings(likeMode, songs),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        advanceUntilIdle()
        return engine
    }

    private fun TestScope.walk(engine: StationEngine, picks: Int): List<String> {
        engine.start(PlaySource.LikedRadio)
        advanceUntilIdle()
        return walkFrom(engine, 0, picks)
    }

    private val likes = (1..10).map { video("LIKE$it") }
    private val likeIds = likes.map { it.videoId }
    private fun mixHead(likeId: String, n: Int) = fakeMix(likeId).drop(1).take(n).map { it.videoId }

    @Before fun setUp() = silenceStationLog()

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `each like is followed by N tracks of its own Mix, in Mix order`() = runTest(StandardTestDispatcher()) {
        for (n in listOf(AppSettings.DEFAULT_SONGS_PER_LIKE, AppSettings.MAX_SONGS_PER_LIKE)) {
            songs.value = n
            val played = walk(engine(likes), picks = (n + 1) * 4)

            val units = played.chunked(n + 1)
            units.forEach { unit ->
                val like = unit.first()
                assertTrue("N=$n: a unit without a like first: $played", like in likeIds)
                assertEquals("N=$n: $played", mixHead(like, n), unit.drop(1))
            }
        }
    }

    @Test
    fun `with no songs added per like liked radio plays likes only, and keeps going`() = runTest(StandardTestDispatcher()) {
        songs.value = 0
        val played = walk(engine(likes), picks = likes.size + 5)

        assertEquals("the station stalled: $played", likes.size + 5, played.size)
        assertTrue("a non-like played: $played", played.all { it in likeIds })
        assertTrue("no Mix is needed: $mixSeeds", mixSeeds.isEmpty())
    }

    @Test
    fun `no like replays before every like played, and Mix tracks never repeat or are likes`() = runTest(StandardTestDispatcher()) {
        val engine = engine(likes)
        val played = walk(engine, picks = (likes.size + 3) * (songs.value + 1))

        val likePlays = played.filter { it in likeIds }
        assertEquals("a like replayed inside its round: $likePlays", likeIds.toSet(), likePlays.take(likes.size).toSet())
        assertNotEquals("shuffle on, yet round 1 was newest first", likeIds, likePlays.take(likes.size))
        assertTrue("the second round never started", likePlays.size > likes.size)
        val news = played.filter { it !in likeIds }
        assertEquals("a Mix track replayed", news.size, news.distinct().size)
        assertTrue(mixSeeds.all { it in likeIds })
    }

    @Test
    fun `shuffle is on by default, and off plays the likes newest first per round`() = runTest(StandardTestDispatcher()) {
        val engine = engine(likes)
        val n = songs.value
        val first = walk(engine, picks = 1).single()
        assertTrue(engine.state.value.shuffle)

        engine.setShuffle(false)
        advanceUntilIdle()
        val played = walkFrom(engine, 1, count = n + (likes.size * 2 - 1) * (n + 1))

        val likePlays = played.filter { it in likeIds }
        assertEquals("rest of the round", likeIds - first, likePlays.take(likes.size - 1))
        assertEquals("next round", likeIds, likePlays.drop(likes.size - 1).take(likes.size))
        played.drop(n).chunked(n + 1).take(likes.size - 1).forEach { unit ->
            assertEquals(mixHead(unit.first(), n), unit.drop(1))
        }
    }

    @Test
    fun `a small library still plays and never repeats a like back to back`() = runTest(StandardTestDispatcher()) {
        songs.value = 0
        val two = likes.take(2)
        val played = walk(engine(two), picks = 12)

        assertEquals("the station stalled: $played", 12, played.size)
        played.zipWithNext().forEach { (a, b) -> assertNotEquals("$a twice: $played", a, b) }
    }

    @Test
    fun `a like in liked radio blends N of its Mix in, then the queue resumes`() = runTest(StandardTestDispatcher()) {
        val n = songs.value
        val engine = engine(likes)
        walk(engine, picks = 2)
        val liked = engine.state.value.current!!
        assertFalse("precondition: a Mix track plays", liked.videoId in likeIds)
        val before = engine.queuedIds()

        engine.toggleLike(liked)
        advanceUntilIdle()

        assertEquals(mixHead(liked.videoId, n) + before, engine.queuedIds())
        assertEquals(PlaySource.LikedRadio, engine.state.value.source)
    }

    @Test
    fun `liked unlike removes the track from the queue, and that is all`() = runTest(StandardTestDispatcher()) {
        val three = likes.take(3)
        val engine = engine(three)
        engine.start(PlaySource.Liked(shuffle = false))
        engine.onQueueAdvanced(0)
        assertEquals(three.drop(1).map { it.videoId }, engine.queuedIds())

        engine.toggleLike(three[1])
        advanceUntilIdle()

        val state = engine.state.value
        assertEquals(listOf(three[2].videoId), engine.queuedIds())
        assertEquals(three[2].videoId, engine.queue().idAt(1))
        assertEquals(PlaySource.Liked(shuffle = false), state.source)
        assertTrue("unlike in Liked fetched a Mix: $mixSeeds", mixSeeds.isEmpty())
        verify { likedTracks.set(three[1], false) }
    }

    @Test
    fun `liked rolls into liked radio when its last track starts`() = runTest(StandardTestDispatcher()) {
        val three = likes.take(3)
        val engine = engine(three)
        engine.start(PlaySource.Liked(shuffle = false))

        val played = mutableListOf<String>()
        for (i in three.indices) {
            assertEquals(PlaySource.Liked(shuffle = false), engine.state.value.source)
            played += engine.queue().idAt(i)!!
            engine.onQueueAdvanced(i)
        }

        assertEquals(three.map { it.videoId }, played)
        assertEquals(PlaySource.LikedRadio, engine.state.value.source)
        val next = engine.queue().idAt(three.size)
        assertNotNull("rolled over without a next track", next)
        assertNotEquals("the last like opened the next round", three.last().videoId, next)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StationCollectionsTest {
    private fun video(id: String, uploader: String = "u") = YtVideo(
        videoId = id.padEnd(11, 'x').take(11),
        title = "t-$id",
        uploader = uploader,
        durationSeconds = 240,
        thumbnailUrl = null,
    )

    private val ten = (1..10).map { video("LIST$it") }
    private val three = ten.take(3)
    private fun ids(videos: List<YtVideo>) = videos.map { it.videoId }

    private val playlist = PlaySource.Playlist(YouTubeUrls.playlistUrl(PLAYLIST_ID), "Trip")

    private val landed = playlist.copy(title = "Road trip")
    private val channel = PlaySource.Channel("https://www.youtube.com/channel/UCx", "Some Channel")

    private fun fakeExtractor(tracks: List<YtVideo> = ten): YouTubeExtractorClient {
        val extractor = mockk<YouTubeExtractorClient>()
        coEvery { extractor.playlistInfo(any(), any()) } returns YtVideoList("Road trip", tracks)
        coEvery { extractor.channelUploads(any(), any()) } returns
            YtVideoList("Some Channel", tracks)
        coEvery { extractor.mix(any(), any()) } answers {
            val seed = firstArg<String>()
            (1..10).map { video("m$it-$seed".take(11)) }
        }
        coEvery { extractor.relatedStreams(any()) } returns
            YtRelated(seedViews = -1, videos = emptyList())
        coEvery { extractor.resolveAudioStream(any(), any()) } answers { stream(firstArg()) }
        return extractor
    }

    private val likedTracks = mockk<LikedTracks>(relaxed = true)
    private val likeMode = MutableStateFlow(LikeMode.BLEND)
    private val songs = MutableStateFlow(AppSettings.DEFAULT_SONGS_PER_LIKE)

    private fun TestScope.engine(extractor: YouTubeExtractorClient = fakeExtractor()) = StationEngine(
        extractor,
        this,
        mockk(relaxed = true),
        likedTracks,
        Random(RANDOM_SEED),
        fakeSettings(likeMode, songs),
        mockk(relaxed = true),
        mockk(relaxed = true),
    )

    private fun TestScope.started(
        source: PlaySource,
        extractor: YouTubeExtractorClient = fakeExtractor(),
    ): StationEngine {
        val engine = engine(extractor)
        advanceUntilIdle()
        engine.start(source)
        advanceUntilIdle()
        return engine
    }

    @Before fun setUp() = silenceStationLog()

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `a playlist plays its list in order, each track naming the playlist`() = runTest(StandardTestDispatcher()) {
        val engine = started(playlist)

        val state = engine.state.value
        assertTrue(state.isCollection)
        assertEquals("YouTube's name replaces the start's", landed, state.source)
        val why = Why(Provenance.PLAYLIST, null, landed.title)
        assertTrue(state.queued.all { it.why == why })
        assertEquals(ids(ten), engine.advanceThrough(ten.size))
    }

    @Test
    fun `a playlist YouTube gave no name keeps the title it started with`() = runTest(StandardTestDispatcher()) {
        val extractor = fakeExtractor()
        coEvery { extractor.playlistInfo(any(), any()) } returns YtVideoList(PLAYLIST_ID, ten)

        val engine = started(playlist, extractor)

        assertEquals(playlist, engine.state.value.source)
    }

    @Test
    fun `a jump in a list plays the tapped track and the list goes on from its place`() = runTest(StandardTestDispatcher()) {
        val engine = started(playlist)
        engine.advanceThrough(1)

        val at = engine.jumpTo(ten[4])

        assertEquals(1, at)
        assertEquals(ten[4].videoId, engine.queue().idAt(1))
        engine.onQueueAdvanced(1)
        assertEquals(ten[4], engine.state.value.current)
        assertEquals("the tracks between were passed over", ids(ten.drop(5)), engine.queuedIds())

        val again = engine.jumpTo(ten[0], again = true)!!
        engine.onQueueAdvanced(again)
        assertEquals(ten[0], engine.state.value.current)
        assertEquals(ids(ten.drop(5)), engine.queuedIds())
    }

    @Test
    fun `shuffle keeps the playing track and reorders only the rest`() = runTest(StandardTestDispatcher()) {
        val engine = started(playlist)
        engine.advanceThrough(3)

        engine.setShuffle(true)

        val state = engine.state.value
        assertTrue(state.shuffle)
        assertEquals(ten[2], state.current)
        assertEquals(ten[2].videoId, engine.queue().idAt(2))
        val rest = ids(ten.drop(3))
        assertEquals(rest.sorted(), engine.queuedIds().sorted())
        assertNotEquals("the rest kept list order", rest, engine.queuedIds())
    }

    @Test
    fun `shuffle off restores list order after the playing track`() = runTest(StandardTestDispatcher()) {
        val engine = started(playlist.copy(shuffle = true))
        assertTrue(engine.state.value.shuffle)
        assertNotEquals("precondition: started shuffled", ids(ten.drop(1)), engine.queuedIds())
        engine.onQueueAdvanced(0)
        if (engine.state.value.current == ten.last()) engine.onQueueAdvanced(1)
        val now = engine.state.value.current!!

        engine.setShuffle(false)

        assertEquals(now, engine.state.value.current)
        assertEquals(ids(ten).dropWhile { it != now.videoId }.drop(1), engine.queuedIds())
    }

    @Test
    fun `repeat all wraps to the start, and a shuffled list reshuffles per round`() = runTest(StandardTestDispatcher()) {
        val inOrder = started(playlist, fakeExtractor(three))
        inOrder.cycleRepeat()
        assertEquals(RepeatMode.ALL, inOrder.state.value.repeat)

        assertEquals(ids(three + three + three.take(1)), inOrder.advanceThrough(7))
        assertEquals(landed, inOrder.state.value.source)

        val shuffled = started(playlist.copy(shuffle = true))
        shuffled.cycleRepeat()
        val rounds = shuffled.advanceThrough(ten.size * 3).chunked(ten.size)
        rounds.forEach { assertEquals(ids(ten).sorted(), it.sorted()) }
        assertTrue("every round in the same order: $rounds", rounds.distinct().size > 1)
    }

    @Test
    fun `repeat off rolls a playlist or a channel into a song radio from its last track`() = runTest(StandardTestDispatcher()) {
        for (source in listOf(playlist, channel)) {
            val engine = started(source, fakeExtractor(three))

            assertEquals(ids(three), engine.advanceThrough(three.size))

            val rolled = engine.state.value
            assertEquals(PlaySource.SongRadio(three.last()), rolled.source)
            assertFalse(rolled.isCollection)
            advanceUntilIdle()
            val next = engine.state.value.queued
            assertTrue("$source rolled into nothing", next.isNotEmpty())
            assertTrue(next.all { it.why.fromVideoId == three.last().videoId })
            assertEquals(next.first().video.videoId, engine.queue().idAt(three.size))
        }
    }

    @Test
    fun `repeat cycles off, all, one, off - one wraps on next, off on the last track rolls over`() = runTest(StandardTestDispatcher()) {
        val engine = started(playlist, fakeExtractor(three))
        assertEquals(RepeatMode.OFF, engine.state.value.repeat)
        engine.cycleRepeat()
        assertEquals(RepeatMode.ALL, engine.state.value.repeat)
        engine.cycleRepeat()
        assertEquals(RepeatMode.ONE, engine.state.value.repeat)

        engine.advanceThrough(three.size)
        assertEquals("Next past the last track", three.first().videoId, engine.queue().idAt(3))

        engine.cycleRepeat()
        val state = engine.state.value
        assertEquals(RepeatMode.OFF, state.repeat)
        assertEquals(PlaySource.SongRadio(three.last()), state.source)
    }

    @Test
    fun `a start resets shuffle and repeat, and liked shuffle seeds the flag`() = runTest(StandardTestDispatcher()) {
        coEvery { likedTracks.all() } returns three
        val engine = engine()
        advanceUntilIdle()
        engine.start(PlaySource.Liked(shuffle = true))
        assertTrue(engine.state.value.shuffle)
        engine.cycleRepeat()
        assertEquals(RepeatMode.ALL, engine.state.value.repeat)

        engine.start(playlist)
        advanceUntilIdle()

        val state = engine.state.value
        assertEquals(landed, state.source)
        assertFalse(state.shuffle)
        assertEquals(RepeatMode.OFF, state.repeat)
    }

    @Test
    fun `radios shuffle but have no repeat`() = runTest(StandardTestDispatcher()) {
        val engine = started(PlaySource.SongRadio(video("SEED")))

        engine.setShuffle(true)
        engine.cycleRepeat()

        val state = engine.state.value
        assertFalse(state.isCollection)
        assertTrue(state.shuffle)
        assertEquals(RepeatMode.OFF, state.repeat)
    }

    private fun mixOf(seed: YtVideo, n: Int) = (1..n).map { video("m$it-${seed.videoId}".take(11)).videoId }

    @Test
    fun `blend in a list plays N extras next, and the list's place and repeat round stay`() = runTest(StandardTestDispatcher()) {
        val n = songs.value
        for (source in listOf(playlist, channel)) {
            val engine = started(source, fakeExtractor(three))
            engine.cycleRepeat()
            engine.advanceThrough(1)

            engine.toggleLike(three[0])
            advanceUntilIdle()

            val played = walkFrom(engine, 1, n + three.size * 2 - 1)
            assertEquals(
                "$source",
                mixOf(three[0], n) + ids(three.drop(1)) + ids(three),
                played,
            )
            assertTrue(engine.state.value.isCollection)
        }
    }

    @Test
    fun `with no songs added per like a like in a list changes nothing`() = runTest(StandardTestDispatcher()) {
        songs.value = 0
        val extractor = fakeExtractor()
        val engine = started(playlist, extractor)
        engine.advanceThrough(2)
        val before = engine.queuedIds()

        engine.toggleLike(ten[1])
        advanceUntilIdle()

        assertEquals(before, engine.queuedIds())
        coVerify(exactly = 0) { extractor.mix(any(), any()) }
    }

    @Test
    fun `switch now in a playlist leaves it for a radio from the liked song, which chains`() = runTest(StandardTestDispatcher()) {
        likeMode.value = LikeMode.SWITCH_NOW
        val engine = started(playlist)
        engine.cycleRepeat()
        engine.advanceThrough(2)
        val liked = ten[1]

        engine.toggleLike(liked)
        advanceUntilIdle()

        val state = engine.state.value
        assertEquals(PlaySource.SongRadio(liked), state.source)
        assertFalse(state.isCollection)
        assertEquals(RepeatMode.OFF, state.repeat)
        assertEquals(liked, state.current)
        assertEquals(mixOf(liked, 10), engine.queuedIds())

        val played = walkFrom(engine, 2, 10 - StationEngine.CHAIN_BELOW + 1)
        val chained = engine.state.value.queued.filter { it.why.fromVideoId == played.last() }
        assertTrue("no chain after the switch: ${engine.queuedIds()}", chained.isNotEmpty())
        assertTrue(ids(ten).none { it in played })
    }

    @Test
    fun `the old station plays until the list lands, and a newer start drops it`() = runTest(StandardTestDispatcher()) {
        val seed = PlaySource.SongRadio(video("SEED"))
        val engine = started(seed)

        engine.start(playlist)
        assertEquals("switched before the list landed", seed, engine.state.value.source)
        advanceUntilIdle()
        assertEquals(landed, engine.state.value.source)

        engine.start(channel)
        val newer = PlaySource.SongRadio(video("NEWER"))
        engine.start(newer)
        advanceUntilIdle()
        assertEquals(newer, engine.state.value.source)
    }

    @Test
    fun `a channel without a videos tab plays its own uploads from a search`() = runTest(StandardTestDispatcher()) {
        val extractor = fakeExtractor()
        coEvery { extractor.channelUploads(any(), any()) } returns
            YtVideoList("Some Channel - Topic", emptyList())
        val byName = video("BYNAME", uploader = channel.name)
        val byUrl = video("BYURL", uploader = "Topic").copy(uploaderUrl = channel.url)
        val other = video("OTHER", uploader = "Someone else")
        coEvery { extractor.search(any(), any()) } returns listOf(other, byName, byUrl)

        val engine = started(channel, extractor)

        val state = engine.state.value
        assertEquals(channel, state.source)
        val played = listOfNotNull(state.current) + state.queued.map { it.video }
        assertEquals(listOf(byName, byUrl), played)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StationPreviousTest {
    private val likedTracks = mockk<LikedTracks>(relaxed = true)
    private val list = (1..6).map { video("LIST$it") }
    private val likes = (1..6).map { video("LIKE$it") }
    private val likeIds = likes.map { it.videoId }
    private val seed = video("SEED")

    private val playlist = PlaySource.Playlist(YouTubeUrls.playlistUrl(PLAYLIST_ID), "Trip")
    private val landed = playlist.copy(title = "Road trip")
    private val channel = PlaySource.Channel("https://www.youtube.com/channel/UCx", "Some Channel")

    private fun TestScope.engine(): StationEngine {
        val extractor = fakeExtractor()
        coEvery { extractor.playlistInfo(any(), any()) } returns YtVideoList("Road trip", list)
        coEvery { extractor.channelUploads(any(), any()) } returns
            YtVideoList("Some Channel", list)
        coEvery { likedTracks.all() } returns likes
        val engine = StationEngine(
            extractor,
            this,
            mockk(relaxed = true),
            likedTracks,
            Random(RANDOM_SEED),
            fakeSettings(),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        advanceUntilIdle()
        return engine
    }

    private fun TestScope.change(engine: StationEngine, source: PlaySource) {
        engine.start(source)
        advanceUntilIdle()
        engine.onQueueAdvanced(0)
        advanceUntilIdle()
    }

    private fun TestScope.previous(engine: StationEngine): Boolean {
        val at = engine.previous(positionMs = 0) ?: return false
        engine.onQueueAdvanced(at)
        advanceUntilIdle()
        return true
    }

    private fun TestScope.next(engine: StationEngine, count: Int): List<String> = walkFrom(engine, engine.queue().currentIndex + 1, count)

    private fun StationEngine.currentId() = state.value.current?.videoId

    @Before fun setUp() = silenceStationLog()

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `past the threshold previous restarts the track and changes nothing`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        change(engine, PlaySource.SongRadio(seed))
        next(engine, 2)
        val now = engine.currentId()
        val upcoming = engine.queuedIds()

        assertNull(engine.previous(StationEngine.PREVIOUS_RESTART_MS + 1))

        assertEquals(now, engine.currentId())
        assertEquals(upcoming, engine.queuedIds())
        assertNotNull(
            "at the threshold Previous goes back",
            engine.previous(StationEngine.PREVIOUS_RESTART_MS),
        )
    }

    @Test
    fun `previous in a radio plays the songs before again, and next returns to where it was`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        change(engine, PlaySource.SongRadio(seed))
        val played = listOf(seed.videoId) + next(engine, 2)
        val upcoming = engine.queuedIds()

        assertTrue(previous(engine))
        assertEquals(played[1], engine.currentId())
        assertEquals(listOf(played[2]) + upcoming, engine.queuedIds().take(upcoming.size + 1))

        assertTrue(previous(engine))
        assertEquals(played[0], engine.currentId())
        assertEquals(played.drop(1) + upcoming, engine.queuedIds().take(upcoming.size + 2))
        assertEquals(PlaySource.SongRadio(seed), engine.state.value.source)

        assertFalse("nothing earlier: a restart", previous(engine))
        assertEquals(played[0], engine.currentId())

        assertEquals(played.drop(1) + upcoming.first(), next(engine, 3))
    }

    @Test
    fun `previous from a channel's first song restores the radio, its song and its upcoming list`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        change(engine, PlaySource.SongRadio(seed))
        val played = listOf(seed.videoId) + next(engine, 2)
        engine.setShuffle(true)
        val upcoming = engine.queuedIds()

        change(engine, channel)
        assertEquals(channel, engine.state.value.source)
        assertEquals(list.first().videoId, engine.currentId())

        assertTrue(previous(engine))
        val state = engine.state.value
        assertEquals(PlaySource.SongRadio(seed), state.source)
        assertEquals(played[2], engine.currentId())
        assertEquals(0, engine.queue().currentIndex)
        assertEquals(upcoming, engine.queuedIds().take(upcoming.size))
        assertTrue(state.shuffle)

        assertTrue(previous(engine))
        assertEquals(played[1], engine.currentId())
    }

    @Test
    fun `previous walks back through at most STATION_BACK_STACK stations, newest first`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        val stations = (0..StationEngine.STATION_BACK_STACK + 1).map {
            PlaySource.SongRadio(video("ST$it"))
        }
        stations.forEach { change(engine, it) }

        val restored = mutableListOf<PlaySource?>()
        while (previous(engine)) restored += engine.state.value.source

        assertEquals(
            stations.dropLast(1).reversed().take(StationEngine.STATION_BACK_STACK),
            restored,
        )
        assertEquals(stations[1].seed.videoId, engine.currentId())
    }

    @Test
    fun `a restored list goes on from its place, in its repeat round`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        change(engine, playlist)
        engine.cycleRepeat()
        next(engine, 2)
        val left = list[2]
        assertEquals(left.videoId, engine.currentId())

        change(engine, PlaySource.SongRadio(left))
        assertTrue(previous(engine))

        val state = engine.state.value
        assertEquals(landed, state.source)
        assertTrue(state.isCollection)
        assertEquals(RepeatMode.ALL, state.repeat)
        assertEquals(left.videoId, engine.currentId())
        val ids = list.map { it.videoId }
        assertEquals(
            "the rest of the round, then the next one",
            ids.drop(3) + ids.take(3),
            next(engine, list.size),
        )
    }

    @Test
    fun `a restored liked radio goes on with its round of likes`() = runTest(StandardTestDispatcher()) {
        val n = AppSettings.DEFAULT_SONGS_PER_LIKE
        val engine = engine()
        change(engine, PlaySource.LikedRadio)
        val before = listOf(engine.currentId()!!) + next(engine, n + 1)
        val left = engine.currentId()
        assertTrue("precondition: a like plays", left in likeIds)
        val upcoming = engine.queuedIds()

        change(engine, PlaySource.SongRadio(video("ELSEWHERE")))
        assertTrue(previous(engine))

        val state = engine.state.value
        assertEquals(PlaySource.LikedRadio, state.source)
        assertTrue(state.shuffle)
        assertEquals(left, engine.currentId())
        assertEquals(upcoming, engine.queuedIds().take(upcoming.size))
        val likesLeft = likes.size - 2
        val after = next(engine, n + likesLeft * (n + 1))
        val likePlays = (before + after).filter { it in likeIds }
        assertEquals("a like replayed inside its round: $likePlays", likeIds.toSet(), likePlays.toSet())
        assertEquals(likes.size, likePlays.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StationPairsTest {
    private val likedTracks = mockk<LikedTracks>(relaxed = true)
    private val songs = MutableStateFlow(AppSettings.DEFAULT_SONGS_PER_LIKE)

    private fun TestScope.engine(likes: List<YtVideo> = emptyList()): StationEngine {
        coEvery { likedTracks.all() } returns likes
        val engine = StationEngine(
            fakeExtractor(related = ::fakeRelated),
            this,
            mockk(relaxed = true),
            likedTracks,
            Random(RANDOM_SEED),
            fakeSettings(songs = songs),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        advanceUntilIdle()
        return engine
    }

    private fun mixIds(seed: String) = fakeMix(seed).drop(1).map { it.videoId }

    private fun assertPairs(mix: List<String>, related: List<String>, actual: List<String>): List<Boolean> {
        val pairs = minOf(mix.size, related.size)
        val heads = actual.take(pairs * 2).chunked(2).mapIndexed { i, pair ->
            assertEquals("pair $i of $actual", setOf(mix[i], related[i]), pair.toSet())
            pair.first() == mix[i]
        }
        assertEquals(mix.drop(pairs) + related.drop(pairs), actual.drop(pairs * 2))
        return heads
    }

    @Before fun setUp() = silenceStationLog()

    @After fun tearDown() = unmockkStatic(Log::class)

    @Test
    fun `a radio is the seed, then its pairs, then the rest of its Mix`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        val seed = video("SEED")
        engine.start(PlaySource.SongRadio(seed))
        advanceUntilIdle()

        val related = relatedHead(seed.videoId)
        val mixLed = assertPairs(mixIds(seed.videoId), related, engine.queuedIds())
        assertTrue("the Mix led every pair: $mixLed", false in mixLed)
        assertTrue("related led every pair: $mixLed", true in mixLed)
        val queued = engine.state.value.queued
        val fromRelated = queued.filter { it.video.videoId in related }
        assertTrue(fromRelated.all { it.why == Why(Provenance.RELATED, seed.videoId, seed.title) })
        assertTrue(
            (queued - fromRelated.toSet())
                .all { it.why == Why(Provenance.MIX, seed.videoId, seed.title) },
        )
    }

    @Test
    fun `a blend like puts N over 2 pairs of the liked song next, then the queue resumes`() = runTest(StandardTestDispatcher()) {
        songs.value = 4
        val engine = engine()
        engine.start(PlaySource.SongRadio(video("SEED")))
        advanceUntilIdle()
        engine.advanceThrough(2)
        advanceUntilIdle()
        val liked = engine.state.value.current!!
        val before = engine.queuedIds()

        engine.toggleLike(liked)
        advanceUntilIdle()

        val after = engine.queuedIds()
        assertPairs(
            mixIds(liked.videoId).take(2),
            relatedHead(liked.videoId, 2),
            after.take(4),
        )
        assertEquals(before, after.drop(4))
    }

    @Test
    fun `liked radio puts N over 2 pairs of each like after it`() = runTest(StandardTestDispatcher()) {
        val likes = (1..10).map { video("LIKE$it") }
        val likeIds = likes.map { it.videoId }
        val engine = engine(likes)
        engine.start(PlaySource.LikedRadio)
        advanceUntilIdle()
        val n = songs.value

        val played = walkFrom(engine, 0, (n + 1) * 4)

        played.chunked(n + 1).forEach { unit ->
            val like = unit.first()
            assertTrue("a unit without a like first: $played", like in likeIds)
            assertPairs(mixIds(like).take(n / 2), relatedHead(like, n / 2), unit.drop(1))
        }
    }
}
