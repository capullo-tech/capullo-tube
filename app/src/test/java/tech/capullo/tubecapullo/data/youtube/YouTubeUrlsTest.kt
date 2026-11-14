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
            "https://www.youtube.com/watch?feature=share&v=$id&t=42",
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
            "https://www.youtube.com/feed/subscriptions",
            "https://youtu.be/dQw4w9WgXc",
            "https://youtu.be/dQw4w9WgXcQQ",
        ).forEach { assertNull("should not parse: $it", YouTubeUrls.parseVideoId(it)) }
    }

    @Test
    fun `a bare 11-char id only counts when it is the whole string`() {
        assertEquals(id, YouTubeUrls.parseVideoId(id))
        assertNull(YouTubeUrls.parseVideoId("check out $id today"))
    }

    @Test
    fun `parses playlist ids`() {
        val list = "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"
        assertEquals(list, YouTubeUrls.parsePlaylistId("https://www.youtube.com/playlist?list=$list"))
        assertEquals(list, YouTubeUrls.parsePlaylistId("https://www.youtube.com/watch?v=$id&list=$list"))
        assertEquals(list, YouTubeUrls.parsePlaylistId(list))
        assertTrue(YouTubeUrls.isPlaylist("https://www.youtube.com/playlist?list=$list"))
    }

    @Test
    fun `a bare video id is not treated as a playlist`() {
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
        assertNull(YouTubeUrls.streamExpiryEpochSeconds("https://googlevideo.com/videoplayback?itag=140"))
        assertNull(YouTubeUrls.streamExpiryEpochSeconds("https://googlevideo.com/videoplayback?expire=soon"))
    }

    @Test
    fun `mix urls carry both the seed video and the RD list id`() {
        val url = YouTubeUrls.mixUrl(id)
        assertEquals(id, YouTubeUrls.parseVideoId(url))
        assertEquals("RD$id", YouTubeUrls.parsePlaylistId(url))
        assertTrue(YouTubeUrls.isPlaylist("RD$id"))
    }

    @Test
    fun `round-trips ids through the url builders`() {
        assertEquals(id, YouTubeUrls.parseVideoId(YouTubeUrls.watchUrl(id)))
        val list = "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"
        assertEquals(list, YouTubeUrls.parsePlaylistId(YouTubeUrls.playlistUrl(list)))
    }
}
