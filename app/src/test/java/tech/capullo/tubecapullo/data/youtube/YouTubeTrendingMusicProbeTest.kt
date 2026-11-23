package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class YouTubeTrendingMusicProbeTest {
    private lateinit var client: YouTubeExtractorClient

    @Before
    fun setUp() {
        assumeTrue("set YT_SMOKE=1 to run the chart probe", System.getenv("YT_SMOKE") == "1")
        client = probeClient()
    }

    @Test
    fun `q1 chart fetch returns items for US and a non-US region`() = runBlocking {
        for (region in listOf("US", "NI", "JP")) {
            val chart = client.trendingMusic(region)
            val items = chart.videos.getOrThrow()
            println("PROBE q1 region=$region count=${items.size} http=${chart.httpStatus}")
            println("PROBE q1 region=$region first5=${items.take(5).map { "${it.videoId}|${it.title.take(40)}" }}")
            assertTrue("Q1 FAIL: $region chart returned ${items.size} items (<10)", items.size >= 10)
            items.forEach { assertTrue("bad id ${it.videoId}", it.videoId.length == 11) }
        }
    }

    @Test
    fun `q2 art availability clears the art-card bar`() = runBlocking {
        val items = client.trendingMusic("US", limit = 50).videos.getOrThrow()
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
        val items = client.trendingMusic("US", limit = 50).videos.getOrThrow()
        val inWindow = items.count { it.durationSeconds in 60..15 * 60 }
        val unknown = items.count { it.durationSeconds == 0L }
        val share = if (items.isEmpty()) 0.0 else inWindow.toDouble() / items.size
        println("PROBE q3 items=${items.size} inWindow=$inWindow unknownDuration=$unknown share=$share")
    }

    @Test
    fun `q4 snapshot stability + latency - measurement only`() = runBlocking {
        val t0 = System.nanoTime()
        val a = client.trendingMusic("US", limit = 50).videos.getOrThrow().map { it.videoId }.toSet()
        val t1 = System.nanoTime()
        val b = client.trendingMusic("US", limit = 50).videos.getOrThrow().map { it.videoId }.toSet()
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

    @Test
    fun `q5 every offered region has a trending chart`() = runBlocking {
        val missing = client.supportedRegions().filter { client.trendingMusic(it, limit = 1).videos.getOrThrow().isEmpty() }
        println("PROBE q5 regions=${client.supportedRegions().size} missing=$missing")
        assertTrue("Q5 FAIL: no trending chart for $missing", missing.isEmpty())
    }
}
