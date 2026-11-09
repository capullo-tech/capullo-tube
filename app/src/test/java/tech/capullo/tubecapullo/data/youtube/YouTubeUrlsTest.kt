package tech.capullo.tubecapullo.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeUrlsTest {

    private val id = "dQw4w9WgXcQ"

    @Test
    fun `parses video id from the shapes people actually paste`() {
        val urls = listOf(
            id,
            "https://www.youtube.com/watch?v=$id",
            "http://youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id",
            "www.youtube.com/watch?v=$id",
            "https://youtu.be/$id",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube.com/live/$id",
            // Extra params, and v= not first.
            "https://www.youtube.com/watch?feature=share&v=$id&t=42",
            // Fragment after the useful part.
            "https://www.youtube.com/watch?v=$id#t=1m",
        )
        urls.forEach { assertEquals("failed for: $it", id, YouTubeUrls.parseVideoId(it)) }
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(id, YouTubeUrls.parseVideoId("  https://youtu.be/$id \n"))
    }

    @Test
    fun `rejects non-youtube and malformed input`() {
        listOf(
            "",
            "   ",
            "not a url",
            "https://vimeo.com/watch?v=$id",
            "https://evil.com/watch?v=$id",
            // Right host, no video anywhere.
            "https://www.youtube.com/feed/subscriptions",
            // Wrong length - 10 and 12 chars.
            "https://youtu.be/dQw4w9WgXc",
            "https://youtu.be/dQw4w9WgXcQQ",
        ).forEach { assertNull("should not parse: $it", YouTubeUrls.parseVideoId(it)) }
    }

    @Test
    fun `a bare 11-char id only counts when it is the whole string`() {
        assertEquals(id, YouTubeUrls.parseVideoId(id))
        // The same 11 chars embedded in prose must not be mistaken for an ID.
        assertNull(YouTubeUrls.parseVideoId("check out $id today"))
    }

    @Test
    fun `parses playlist ids`() {
        val list = "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"
        assertEquals(list, YouTubeUrls.parsePlaylistId("https://www.youtube.com/playlist?list=$list"))
        // A watch URL carrying &list= is both a video and a playlist.
        assertEquals(list, YouTubeUrls.parsePlaylistId("https://www.youtube.com/watch?v=$id&list=$list"))
        assertEquals(list, YouTubeUrls.parsePlaylistId(list))
        assertTrue(YouTubeUrls.isPlaylist("https://www.youtube.com/playlist?list=$list"))
    }

    @Test
    fun `a bare video id is not treated as a playlist`() {
        // Both patterns match an 11-char id; without a known playlist prefix it must lose.
        assertNull(YouTubeUrls.parsePlaylistId(id))
        assertFalse(YouTubeUrls.isPlaylist(id))
    }

    @Test
    fun `reads stream expiry from the googlevideo expire param`() {
        val url = "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1755300000&ei=x&itag=140"
        assertEquals(1755300000L, YouTubeUrls.streamExpiryEpochSeconds(url))
    }

    @Test
    fun `missing or unparseable expiry is null rather than zero`() {
        // Null must mean "unknown", never "expired at the epoch" - the cache treats the two the
        // same way today, but a 0 here would silently look like a valid past timestamp.
        assertNull(YouTubeUrls.streamExpiryEpochSeconds("https://googlevideo.com/videoplayback?itag=140"))
        assertNull(YouTubeUrls.streamExpiryEpochSeconds("https://googlevideo.com/videoplayback?expire=soon"))
    }

    @Test
    fun `mix urls carry both the seed video and the RD list id`() {
        val url = YouTubeUrls.mixUrl(id)
        assertEquals(id, YouTubeUrls.parseVideoId(url))
        assertEquals("RD$id", YouTubeUrls.parsePlaylistId(url))
        // An RD id pasted bare is recognised as a playlist (RD is in the known prefixes).
        assertTrue(YouTubeUrls.isPlaylist("RD$id"))
    }

    @Test
    fun `round-trips ids through the url builders`() {
        assertEquals(id, YouTubeUrls.parseVideoId(YouTubeUrls.watchUrl(id)))
        val list = "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"
        assertEquals(list, YouTubeUrls.parsePlaylistId(YouTubeUrls.playlistUrl(list)))
    }
}
