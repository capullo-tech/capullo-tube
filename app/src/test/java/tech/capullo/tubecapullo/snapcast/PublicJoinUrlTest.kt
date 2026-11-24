package tech.capullo.tubecapullo.snapcast

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.audio.snapcast.JoinTarget
import tech.capullo.audio.snapcast.JoinUrl

class PublicJoinUrlTest {
    private val tunnel = "https://able-baker-charlie.trycloudflare.com"

    @Test
    fun `a quick tunnel's base URL becomes its join URL with 443 left implicit`() {
        assertEquals("https://able-baker-charlie.trycloudflare.com/join/", publicJoinUrl(tunnel))
        assertEquals("https://able-baker-charlie.trycloudflare.com/join/", publicJoinUrl("$tunnel/"))
    }

    @Test
    fun `the listener's parser reads the broadcaster's public join URL back as the tunnel`() {
        assertEquals(
            JoinTarget("able-baker-charlie.trycloudflare.com", 443, secure = true),
            JoinUrl.parse(publicJoinUrl(tunnel)),
        )
    }

    @Test
    fun `keeps an explicit port and falls back to 80 for plain http`() {
        assertEquals("https://example.com:8443/join/", publicJoinUrl("https://example.com:8443"))
        assertEquals("http://example.com:80/join/", publicJoinUrl("http://example.com"))
    }

    @Test
    fun `an unparsable, hostless or schemeless URL is shown as-is`() {
        listOf("not a url", "trycloudflare", "https://", "//example.com").forEach {
            assertEquals(it, it, publicJoinUrl(it))
        }
    }
}
