package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Hits YouTube for real. **Skipped unless `YT_SMOKE=1` is set**, so it never runs in CI or in a
 * normal `testDebugUnitTest`:
 *
 * ```
 * YT_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*YouTubeExtractorSmokeTest*'
 * ```
 *
 * This is the canary for the risk the whole YouTube source rests on. It fails in two importantly
 * different ways:
 *
 * - [YouTubeError.BotCheck] - YouTube now demands a proof-of-origin token from this IP. Nothing
 *   plays until a PoTokenProvider exists. This is the go/no-go signal, not a flake.
 * - [YouTubeError.Failed] - upstream parsing broke against a YouTube change; bump the
 *   `newpipeExtractor` pin and re-run.
 */
class YouTubeExtractorSmokeTest {

    private lateinit var client: YouTubeExtractorClient

    @Before
    fun setUp() {
        assumeTrue("set YT_SMOKE=1 to run the network smoke test", System.getenv("YT_SMOKE") == "1")
        client = probeClient()
    }

    @Test
    fun `resolves a playable audio stream for a known video`() = runBlocking {
        // "Me at the zoo" - the first video on YouTube. Public domain-ish, never geo-blocked,
        // 19 seconds long, and about as unlikely to be taken down as anything on the platform.
        val stream = client.resolveAudioStream("jNQXAC9IVRw")

        assertTrue("stream url should be https: ${stream.url}", stream.url.startsWith("https://"))
        assertTrue("bitrate should be positive, was ${stream.bitrateKbps}", stream.bitrateKbps > 0)
        assertTrue("mime should be audio/*, was ${stream.mimeType}", stream.mimeType.startsWith("audio/"))
        // If this is null the URL shape changed and the cache can no longer reason about expiry.
        assertTrue("expiry should have been parsed from the url", stream.expiresAtEpochSeconds != null)
    }

    @Test
    fun `preferM4a still finds an m4a stream`() = runBlocking {
        // The M4 disk cache may depend on M4A being available (see AudioStreamSelector's KDoc).
        // This keeps that door watched even while the streaming default is preferM4a = false.
        val stream = client.resolveAudioStream("jNQXAC9IVRw", preferM4a = true)
        assertTrue("expected an m4a stream when preferM4a=true, got ${stream.mimeType}", stream.isM4a)
    }

    @Test
    fun `search returns videos`() = runBlocking {
        val results = client.search("lofi hip hop radio", limit = 5)
        assertTrue("expected some results", results.isNotEmpty())
        results.forEach {
            assertEquals("id should be 11 chars: ${it.videoId}", 11, it.videoId.length)
            assertTrue("title should not be blank", it.title.isNotBlank())
        }
    }

    @Test
    fun `related streams returns neighbours`() = runBlocking {
        // The station's cheap feed (HANDOFF-bootstrap §5). Mix behaviour has its own probe.
        val related = client.relatedStreams("dQw4w9WgXcQ").videos
        assertTrue("expected related videos", related.isNotEmpty())
        related.forEach { assertEquals("id should be 11 chars: ${it.videoId}", 11, it.videoId.length) }
    }

    @Test
    fun `resolution is cached between calls`() = runBlocking {
        val id = "jNQXAC9IVRw"
        val first = client.resolveAudioStream(id)
        val second = client.resolveAudioStream(id)
        // Same object back means the second call did no network work - which is what makes
        // prefetching the next few tracks affordable.
        assertEquals(first, second)

        client.invalidate(id)
        val third = client.resolveAudioStream(id)
        assertEquals("re-resolution should still pick the same itag", first.itag, third.itag)
    }
}
