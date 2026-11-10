package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * The trending-music kiosk probe. Network-gated behind `YT_SMOKE=1`, like the smoke test and the
 * RD-mix probe:
 *
 * ```
 * YT_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*YouTubeTrendingMusicProbeTest*'
 * ```
 *
 * **Pass criteria were written first**, in `docs/probes/trending-music-criteria.md` - read that
 * before this. Only Q1 (does the fetch work) and Q2 (art availability for art cards) assert;
 * Q3/Q4 are measurements, printed for the run log and recorded in
 * `docs/probes/trending-music-results.md`. A measurement that asserts its own expected value
 * stops being a measurement.
 */
class YouTubeTrendingMusicProbeTest {

    private lateinit var client: YouTubeExtractorClient

    @Before
    fun setUp() {
        assumeTrue("set YT_SMOKE=1 to run the kiosk probe", System.getenv("YT_SMOKE") == "1")
        client = YouTubeExtractorClient(TubeCapulloDownloader())
    }

    @Test
    fun `q1 kiosk fetch returns items`() = runBlocking {
        val items = client.trendingMusic()
        println("PROBE q1 count=${items.size} locale=${Locale.getDefault()}")
        println("PROBE q1 first5=${items.take(5).map { "${it.videoId}|${it.title.take(40)}" }}")
        assertTrue("Q1 FAIL: kiosk returned ${items.size} items (<10)", items.size >= 10)
        items.forEach { assertTrue("bad id ${it.videoId}", it.videoId.length == 11) }
    }

    @Test
    fun `q2 art availability clears the art-card bar`() = runBlocking {
        val items = client.trendingMusic(limit = 50)
        val withArt = items.count { !it.thumbnailUrl.isNullOrBlank() }
        val share = if (items.isEmpty()) 0.0 else withArt.toDouble() / items.size
        println("PROBE q2 items=${items.size} withArt=$withArt share=$share")
        assertTrue(
            "Q2 FAIL: only $withArt/${items.size} items carry thumbnails (<0.9 by the criteria)",
            items.isNotEmpty() && share >= 0.9,
        )
    }

    @Test
    fun `q3 duration shape - measurement only`() = runBlocking {
        val items = client.trendingMusic(limit = 50)
        val inWindow = items.count { it.durationSeconds in 60..15 * 60 }
        val unknown = items.count { it.durationSeconds == 0L }
        val share = if (items.isEmpty()) 0.0 else inWindow.toDouble() / items.size
        println("PROBE q3 items=${items.size} inWindow=$inWindow unknownDuration=$unknown share=$share")
    }

    @Test
    fun `q4 snapshot stability + latency - measurement only`() = runBlocking {
        val t0 = System.nanoTime()
        val a = client.trendingMusic(limit = 50).map { it.videoId }.toSet()
        val t1 = System.nanoTime()
        val b = client.trendingMusic(limit = 50).map { it.videoId }.toSet()
        val t2 = System.nanoTime()
        val overlap = if (minOf(a.size, b.size) == 0) {
            0.0
        } else {
            (a intersect b).size.toDouble() / minOf(a.size, b.size)
        }
        println(
            "PROBE q4 fetchA=${a.size} (${(t1 - t0) / 1_000_000}ms) fetchB=${b.size} " +
                "(${(t2 - t1) / 1_000_000}ms) overlap=$overlap",
        )
    }
}
