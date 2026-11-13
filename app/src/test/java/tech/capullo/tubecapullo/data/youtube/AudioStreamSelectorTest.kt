package tech.capullo.tubecapullo.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioStreamSelectorTest {
    private fun candidate(
        itag: Int,
        bitrateKbps: Int,
        isM4a: Boolean,
        isProgressive: Boolean = true,
        url: String = "https://googlevideo.com/videoplayback?itag=$itag",
    ) = AudioCandidate(
        url = url,
        mimeType = if (isM4a) "audio/mp4" else "audio/webm",
        bitrateKbps = bitrateKbps,
        contentLength = 1_000L * bitrateKbps,
        itag = itag,
        isM4a = isM4a,
        isProgressive = isProgressive,
    )

    private val typical = listOf(
        candidate(itag = 249, bitrateKbps = 50, isM4a = false),
        candidate(itag = 250, bitrateKbps = 70, isM4a = false),
        candidate(itag = 140, bitrateKbps = 128, isM4a = true),
        candidate(itag = 251, bitrateKbps = 160, isM4a = false),
    )

    @Test
    fun `prefers m4a over a higher-bitrate opus when caching`() {
        assertEquals(140, AudioStreamSelector.select(typical, preferM4a = true)?.itag)
    }

    @Test
    fun `takes the highest bitrate when not caching`() {
        assertEquals(251, AudioStreamSelector.select(typical, preferM4a = false)?.itag)
    }

    @Test
    fun `picks the best m4a when several are offered`() {
        val streams = listOf(
            candidate(itag = 139, bitrateKbps = 48, isM4a = true),
            candidate(itag = 140, bitrateKbps = 128, isM4a = true),
            candidate(itag = 141, bitrateKbps = 256, isM4a = true),
        )
        assertEquals(141, AudioStreamSelector.select(streams, preferM4a = true)?.itag)
    }

    @Test
    fun `falls back to opus when m4a is preferred but unavailable`() {
        val opusOnly = typical.filterNot { it.isM4a }
        assertEquals(251, AudioStreamSelector.select(opusOnly, preferM4a = true)?.itag)
    }

    @Test
    fun `skips non-progressive streams`() {
        val streams = listOf(
            candidate(itag = 140, bitrateKbps = 128, isM4a = true, isProgressive = false),
            candidate(itag = 251, bitrateKbps = 160, isM4a = false),
        )
        assertEquals(251, AudioStreamSelector.select(streams, preferM4a = true)?.itag)
    }

    @Test
    fun `skips blank urls`() {
        val streams = listOf(candidate(itag = 140, bitrateKbps = 128, isM4a = true, url = ""))
        assertNull(AudioStreamSelector.select(streams, preferM4a = true))
    }

    @Test
    fun `returns null when there is nothing usable`() {
        assertNull(AudioStreamSelector.select(emptyList(), preferM4a = true))
        assertNull(
            AudioStreamSelector.select(
                listOf(candidate(itag = 140, bitrateKbps = 128, isM4a = true, isProgressive = false)),
                preferM4a = false,
            ),
        )
    }
}
