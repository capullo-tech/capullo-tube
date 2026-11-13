package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class RdMixProbeTest {
    private lateinit var client: YouTubeExtractorClient

    private val seeds = listOf("dQw4w9WgXcQ", "9bZkp7q19f0")

    @Before
    fun setUp() {
        assumeTrue("set YT_SMOKE=1 to run the RD probe", System.getenv("YT_SMOKE") == "1")
        client = probeClient()
    }

    @Test
    fun `q1 mix fetch returns items for both seeds`() = runBlocking {
        seeds.forEach { seed ->
            val mix = client.mix(seed)
            println("PROBE q1 seed=$seed mixCount=${mix.size} first5=${mix.take(5).map { it.videoId }}")
            assertTrue("Q1 FAIL: mix for $seed returned ${mix.size} items (<10)", mix.size >= 10)
            mix.forEach { assertTrue("bad id ${it.videoId}", it.videoId.length == 11) }
        }
    }

    @Test
    fun `q2 snapshot stability - measurement only`() = runBlocking {
        val seed = seeds.first()
        val a = client.mix(seed).map { it.videoId }.toSet()
        val b = client.mix(seed).map { it.videoId }.toSet()
        val overlap = if (minOf(a.size, b.size) == 0) {
            0.0
        } else {
            (a intersect b).size.toDouble() / minOf(a.size, b.size)
        }
        println("PROBE q2 seed=$seed fetchA=${a.size} fetchB=${b.size} overlap=$overlap")
    }

    @Test
    fun `q3 mix vs related overlap - measurement only`() = runBlocking {
        seeds.forEach { seed ->
            val mix = client.mix(seed).map { it.videoId }.toSet()
            val related = client.relatedStreams(seed).videos.map { it.videoId }.toSet()
            val overlap = if (minOf(mix.size, related.size) == 0) {
                0.0
            } else {
                (mix intersect related).size.toDouble() / minOf(mix.size, related.size)
            }
            println(
                "PROBE q3 seed=$seed mix=${mix.size} related=${related.size} " +
                    "shared=${(mix intersect related).size} overlap=$overlap",
            )
        }
    }

    @Test
    fun `q4 bookkeeping - measurement only`() = runBlocking {
        val seed = seeds.first()
        val mix = client.mix(seed)
        println("PROBE q4 seed=$seed seedInOwnMix=${mix.any { it.videoId == seed }} count=${mix.size}")

        val bare = runCatching { client.playlistInfo(YouTubeUrls.mixPlaylistId(seed)).videos }
        println(
            "PROBE q4 bareForm=" +
                bare.fold({ "OK count=${it.size}" }, { "THREW ${it.message}" }),
        )
    }
}
