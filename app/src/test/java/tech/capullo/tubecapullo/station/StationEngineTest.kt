package tech.capullo.tubecapullo.station

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.tubecapullo.data.history.LikedTracks
import tech.capullo.tubecapullo.data.history.PlayHistory
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtStream
import tech.capullo.tubecapullo.data.youtube.YtVideo

/**
 * Station invariants: the seed never replays, no id plays twice, the queue keeps a lookahead, the
 * pool refills from what is actually playing, and out-of-range durations never enter the pool.
 *
 * The extractor is mocked (concrete class, hence mockk) with mix/related shaped like the probe
 * measured: mix carries the seed as its own first item, feeds barely overlap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationEngineTest {

    private fun video(id: String, seconds: Long = 240) = YtVideo(
        videoId = id.padEnd(11, 'x').take(11),
        title = "t-$id",
        uploader = "u",
        durationSeconds = seconds,
        thumbnailUrl = null,
    )

    private fun stream(id: String) = YtStream(
        url = "https://example/$id",
        mimeType = "audio/mp4",
        bitrateKbps = 128,
        contentLength = null,
        expiresAtEpochSeconds = null,
        itag = 140,
        isM4a = true,
    )

    /** Extractor whose mix/related generate per-seed neighbourhoods, seed included in its own mix
     *  (the probe's Q4 finding - the engine must drop it). Records every mix seed asked for. */
    private fun fakeExtractor(mixSeeds: MutableList<String> = mutableListOf()): YouTubeExtractorClient {
        val extractor = mockk<YouTubeExtractorClient>()
        coEvery { extractor.mix(any(), any()) } answers {
            val seed = firstArg<String>()
            mixSeeds += seed
            listOf(video(seed)) + (1..20).map { video("m$it-$seed".take(11)) }
        }
        coEvery { extractor.relatedStreams(any(), any()) } answers {
            val seed = firstArg<String>()
            (1..10).map { video("r$it-$seed".take(11)) }
        }
        coEvery { extractor.resolveAudioStream(any(), any()) } answers { stream(firstArg()) }
        return extractor
    }

    private val playHistory = mockk<PlayHistory>(relaxed = true)
    private val likedTracks = mockk<LikedTracks>(relaxed = true)

    private fun TestScope.engine() = StationEngine(fakeExtractor(), this, playHistory, likedTracks)

    /** Walk the station like the loop would: read idAt, report advanced. */
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

    @Test
    fun `station grows past its seed without user input`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        engine.startStation(video("seed0000000"))
        advanceUntilIdle() // let the initial refill land

        engine.onQueueAdvanced(0)
        assertTrue("queue should have grown a lookahead", engine.queue().size > 1)
    }

    @Test
    fun `no id ever plays twice across 30 advances`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        engine.startStation(video("seed0000000"))
        advanceUntilIdle()

        val played = engine.advanceThrough(30)
        advanceUntilIdle() // refills triggered mid-walk
        assertEquals("expected 30 tracks", 30, played.size)
        assertEquals("a played id returned", played.size, played.distinct().size)
    }

    @Test
    fun `the seed does not re-enter from its own mix`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        val seed = video("seed0000000")
        engine.startStation(seed)
        advanceUntilIdle()

        val played = engine.advanceThrough(20)
        assertEquals("seed plays first", seed.videoId, played.first())
        assertFalse("seed replayed", played.drop(1).contains(seed.videoId))
    }

    @Test
    fun `out-of-range durations never enter the queue`() = runTest(StandardTestDispatcher()) {
        val extractor = mockk<YouTubeExtractorClient>()
        coEvery { extractor.mix(any(), any()) } returns listOf(
            video("short000000", seconds = 20), // jingle
            video("marathon000", seconds = 4 * 3600), // 4h compilation
            video("song0000000", seconds = 200),
        )
        coEvery { extractor.relatedStreams(any(), any()) } returns emptyList()
        coEvery { extractor.resolveAudioStream(any(), any()) } answers { stream(firstArg()) }
        val engine = StationEngine(extractor, this, playHistory, likedTracks)

        engine.startStation(video("seed0000000"))
        advanceUntilIdle()
        val played = engine.advanceThrough(5)

        assertEquals(listOf("seed0000000", "song0000000"), played)
    }

    @Test
    fun `refill draws from the currently playing track, so the station drifts`() = runTest(StandardTestDispatcher()) {
        val mixSeeds = mutableListOf<String>()
        val engine = StationEngine(fakeExtractor(mixSeeds), this, playHistory, likedTracks)
        engine.startStation(video("seed0000000"))
        advanceUntilIdle()

        // Exhaust the pool far enough to force a refill; the refill must be seeded from a track
        // that is NOT the original seed - that is what makes the station drift.
        engine.advanceThrough(25)
        advanceUntilIdle()
        assertTrue("no refill happened at all", mixSeeds.size >= 2)
        assertTrue(
            "refills only ever drew from the original seed: $mixSeeds",
            mixSeeds.drop(1).any { it != "seed0000000" },
        )
    }

    @Test
    fun `advancing records the played track in history`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        engine.startStation(video("seed0000000"))
        advanceUntilIdle()
        engine.advanceThrough(1)

        // onQueueAdvanced is the write point - history backs Home's "Because you played".
        verify { playHistory.record(match { it.videoId == "seed0000000" }) }
    }

    @Test
    fun `liking persists through the liked store`() = runTest(StandardTestDispatcher()) {
        val engine = engine()
        engine.startStation(video("seed0000000"))
        advanceUntilIdle()
        engine.advanceThrough(1)

        engine.toggleLike("seed0000000")
        verify { likedTracks.set(match { it.videoId == "seed0000000" }, true) }

        engine.toggleLike("seed0000000")
        verify { likedTracks.set(match { it.videoId == "seed0000000" }, false) }
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

/**
 * M5 taste invariants. The boost/damp constants are sized to dominate the selection jitter
 * (see their comments), which is what makes these assertions deterministic despite the random
 * spread - a liked neighbourhood MUST outrank any neutral candidate, a skipped one MUST lose
 * to any neutral one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StationTasteTest {

    private fun video(id: String, uploader: String = "u") = YtVideo(
        videoId = id.padEnd(11, 'x').take(11),
        title = "t-$id",
        uploader = uploader,
        durationSeconds = 240,
        thumbnailUrl = null,
    )

    private fun stream(id: String) = YtStream(
        url = "https://example/$id",
        mimeType = "audio/mp4",
        bitrateKbps = 128,
        contentLength = null,
        expiresAtEpochSeconds = null,
        itag = 140,
        isM4a = true,
    )

    /** Per-seed neighbourhoods with distinctive prefixes: mix("AAAA...") yields "mAAA1"-style ids,
     *  so a played id names its provenance and assertions can read it back. */
    private fun fakeExtractor(mixSeeds: MutableList<String> = mutableListOf()): YouTubeExtractorClient {
        val extractor = mockk<YouTubeExtractorClient>()
        coEvery { extractor.mix(any(), any()) } answers {
            val seed = firstArg<String>()
            mixSeeds += seed
            listOf(video(seed)) + (1..15).map { video("m${seed.take(4)}$it") }
        }
        coEvery { extractor.relatedStreams(any(), any()) } answers {
            val seed = firstArg<String>()
            (1..5).map { video("r${seed.take(4)}$it") }
        }
        coEvery { extractor.resolveAudioStream(any(), any()) } answers { stream(firstArg()) }
        return extractor
    }

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

    private val playHistory = mockk<PlayHistory>(relaxed = true)
    private val likedTracks = mockk<LikedTracks>(relaxed = true)

    @Test
    fun `a like shifts the very next picks to the liked neighbourhood`() = runTest(StandardTestDispatcher()) {
        val engine = StationEngine(fakeExtractor(), this, playHistory, likedTracks)
        engine.startStation(video("SEED"))
        advanceUntilIdle()

        // Play seed + two more, then like the third track. Its neighbourhood ids carry its
        // 4-char prefix, so the shift is readable off the played list.
        val before = engine.advanceThrough(3)
        val liked = before.last()
        engine.toggleLike(liked)
        advanceUntilIdle() // the like's re-seeded mix lands in the pool

        val after = StringBuilder()
        val next = mutableListOf<String>()
        var i = 3
        while (next.size < 5) {
            val id = engine.queue().idAt(i) ?: break
            next += id
            engine.onQueueAdvanced(i)
            i++
        }
        after.append(next)
        val fromLiked = next.count { it.startsWith("m${liked.take(4)}") || it.startsWith("r${liked.take(4)}") }
        assertTrue(
            "like did not shift selection: next=$after liked=$liked",
            fromLiked == next.size,
        )
    }

    @Test
    fun `unlike removes the boost`() = runTest(StandardTestDispatcher()) {
        val engine = StationEngine(fakeExtractor(), this, playHistory, likedTracks)
        engine.startStation(video("SEED"))
        advanceUntilIdle()
        val played = engine.advanceThrough(2)
        val id = played.last()

        engine.toggleLike(id)
        assertTrue(id in engine.likedIds.value)
        engine.toggleLike(id)
        assertTrue(id !in engine.likedIds.value)
    }

    @Test
    fun `a skip damps the skipped neighbourhood in favour of neutral candidates`() = runTest(StandardTestDispatcher()) {
        val mixSeeds = mutableListOf<String>()
        val engine = StationEngine(fakeExtractor(mixSeeds), this, playHistory, likedTracks)
        engine.startStation(video("SEED"))
        advanceUntilIdle()

        // Walk until the low-water refill fires from a non-seed track: the pool then holds two
        // provenances - the seed's (neutral) and the refill track's.
        engine.advanceThrough(12)
        advanceUntilIdle()
        val refillSeed = mixSeeds.last()
        assertTrue("no second-provenance refill happened", refillSeed != "SEEDxxxxxxx")

        // Damp the refill track's neighbourhood. Its children carry its 4-char prefix; with
        // damped-max (0.6) below neutral-min (1.0) none may be picked while neutral remains.
        engine.noteSkip(refillSeed)
        val next = mutableListOf<String>()
        var i = 12
        while (next.size < 3) {
            val id = engine.queue().idAt(i) ?: break
            next += id
            engine.onQueueAdvanced(i)
            i++
        }
        val damped = next.filter {
            it.startsWith("m${refillSeed.take(4)}") || it.startsWith("r${refillSeed.take(4)}")
        }
        assertTrue("skip did not damp the neighbourhood: picked $damped of $next", damped.isEmpty())
    }

    @Test
    fun `played history survives a station jump - no replays across seeds`() = runTest(StandardTestDispatcher()) {
        val extractor = mockk<YouTubeExtractorClient>()
        // Both seeds share their entire neighbourhood - the collision case a jump must dedup.
        val shared = (1..10).map { video("shared$it") }
        coEvery { extractor.mix(any(), any()) } answers { listOf(video(firstArg())) + shared }
        coEvery { extractor.relatedStreams(any(), any()) } returns emptyList()
        coEvery { extractor.resolveAudioStream(any(), any()) } answers { stream(firstArg()) }
        val engine = StationEngine(extractor, this, playHistory, likedTracks)

        engine.startStation(video("SEEDA"))
        advanceUntilIdle()
        val first = engine.advanceThrough(5)

        engine.startStation(video("SEEDB"))
        advanceUntilIdle()
        val second = engine.advanceThrough(5)

        val replayed = second.drop(1).filter { it in first }
        assertTrue("jump replayed session tracks: $replayed", replayed.isEmpty())
    }

    @Test
    fun `a like re-seeds a mix from the liked track immediately`() = runTest(StandardTestDispatcher()) {
        val mixSeeds = mutableListOf<String>()
        val engine = StationEngine(fakeExtractor(mixSeeds), this, playHistory, likedTracks)
        engine.startStation(video("SEED"))
        advanceUntilIdle()
        val played = engine.advanceThrough(2)

        engine.toggleLike(played.last())
        advanceUntilIdle()
        assertTrue(
            "like did not trigger a refetch from the liked track: $mixSeeds",
            mixSeeds.contains(played.last()),
        )
    }
}
