package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class YouTubeExtractorSmokeTest {
    private lateinit var client: YouTubeExtractorClient

    @Before
    fun setUp() {
        assumeTrue("set YT_SMOKE=1 to run the network smoke test", System.getenv("YT_SMOKE") == "1")
        client = probeClient()
    }

    @Test
    fun `resolves a playable audio stream for a known video`() = runBlocking {
        val stream = client.resolveAudioStream("jNQXAC9IVRw")

        assertTrue("stream url should be https: ${stream.url}", stream.url.startsWith("https://"))
        assertTrue("bitrate should be positive, was ${stream.bitrateKbps}", stream.bitrateKbps > 0)
        assertTrue("mime should be audio/*, was ${stream.mimeType}", stream.mimeType.startsWith("audio/"))
        assertTrue("expiry should have been parsed from the url", stream.expiresAtEpochSeconds != null)
    }

    @Test
    fun `preferM4a still finds an m4a stream`() = runBlocking {
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
        val related = client.relatedStreams("dQw4w9WgXcQ").videos
        assertTrue("expected related videos", related.isNotEmpty())
        related.forEach { assertEquals("id should be 11 chars: ${it.videoId}", 11, it.videoId.length) }
    }

    @Test
    fun `resolution is cached between calls`() = runBlocking {
        val id = "jNQXAC9IVRw"
        val first = client.resolveAudioStream(id)
        val second = client.resolveAudioStream(id)
        assertEquals(first, second)

        client.invalidate(id)
        val third = client.resolveAudioStream(id)
        assertEquals("re-resolution should still pick the same itag", first.itag, third.itag)
    }
}
