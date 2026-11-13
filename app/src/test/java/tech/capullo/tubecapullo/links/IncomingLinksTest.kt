package tech.capullo.tubecapullo.links

import org.junit.Assert.assertEquals
import org.junit.Test

class IncomingLinksTest {
    private val id = "dQw4w9WgXcQ"
    private val video = LinkTarget.Video(id)
    private val listId = "PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"

    @Test
    fun `finds the link after a title, on the same line or the next`() {
        assertEquals(video, IncomingLinks.find("look https://youtu.be/$id"))
        assertEquals(
            video,
            IncomingLinks.find("Rick Astley - Never Gonna Give You Up\nhttps://youtu.be/$id?si=x"),
        )
    }

    @Test
    fun `accepts every host and path shape the app opens`() {
        listOf(
            "https://www.youtube.com/watch?v=$id&feature=shared",
            "https://youtube.com/watch?v=$id",
            "https://m.youtube.com/watch?v=$id&t=42s",
            "https://music.youtube.com/watch?v=$id&si=Ab3_x",
            "https://youtube.com/shorts/$id?si=Ab3_x",
            "http://youtu.be/$id",
            "youtu.be/$id",
        ).forEach { assertEquals(it, video, IncomingLinks.find(it)) }
    }

    @Test
    fun `strips prose punctuation around the link but keeps id characters`() {
        assertEquals(video, IncomingLinks.find("(https://youtu.be/$id)."))
        assertEquals(video, IncomingLinks.find("\"https://www.youtube.com/watch?v=$id\","))
        assertEquals(video, IncomingLinks.find("Watch:https://youtu.be/$id!"))
        assertEquals(
            LinkTarget.Video("abcdefghi_-"),
            IncomingLinks.find("https://youtu.be/abcdefghi_-."),
        )
    }

    @Test
    fun `playlist links name the playlist, from any host`() {
        val playlist = LinkTarget.Playlist(listId)
        assertEquals(playlist, IncomingLinks.find("https://www.youtube.com/playlist?list=$listId"))
        assertEquals(
            playlist,
            IncomingLinks.find("my list: https://music.youtube.com/playlist?list=$listId&si=x"),
        )
    }

    @Test
    fun `a watch link inside a playlist means the playlist, as in the search box`() {
        assertEquals(
            LinkTarget.Playlist(listId),
            IncomingLinks.find("https://www.youtube.com/watch?v=$id&list=$listId&index=3"),
        )
    }

    @Test
    fun `a watch link inside a Mix means the Mix, like any other playlist`() {
        assertEquals(
            LinkTarget.Playlist("RD$id"),
            IncomingLinks.find("https://www.youtube.com/watch?v=$id&list=RD$id&start_radio=1"),
        )
    }

    @Test
    fun `the first YouTube link that names a video or playlist wins`() {
        assertEquals(
            video,
            IncomingLinks.find("see https://example.com/watch?v=aaaaaaaaaaa https://youtu.be/$id"),
        )
        assertEquals(
            video,
            IncomingLinks.find("https://www.youtube.com/@RickAstleyYT https://youtu.be/$id"),
        )
        assertEquals(
            video,
            IncomingLinks.find("https://youtu.be/$id https://youtu.be/aaaaaaaaaaa"),
        )
    }

    @Test
    fun `a YouTube link that names neither is told apart from no YouTube link`() {
        assertEquals(
            LinkTarget.OtherYouTube,
            IncomingLinks.find("https://www.youtube.com/@RickAstleyYT"),
        )
        assertEquals(LinkTarget.OtherYouTube, IncomingLinks.find("https://www.youtube.com/"))
    }

    @Test
    fun `text without a YouTube link is not YouTube`() {
        assertEquals(LinkTarget.NotYouTube, IncomingLinks.find(null))
        assertEquals(LinkTarget.NotYouTube, IncomingLinks.find("  \n"))
        assertEquals(LinkTarget.NotYouTube, IncomingLinks.find("buy milk"))
        assertEquals(LinkTarget.NotYouTube, IncomingLinks.find("https://vimeo.com/76979871"))
    }

    @Test
    fun `a bare id-shaped word is not a link`() {
        assertEquals(LinkTarget.NotYouTube, IncomingLinks.find(id))
        assertEquals(LinkTarget.NotYouTube, IncomingLinks.find("my song $id"))
    }

    @Test
    fun `look-alike hosts are not YouTube`() {
        assertEquals(
            LinkTarget.NotYouTube,
            IncomingLinks.find("https://notyoutube.com/watch?v=$id"),
        )
        assertEquals(
            LinkTarget.NotYouTube,
            IncomingLinks.find("https://youtube.com.example.net/watch?v=$id"),
        )
    }
}
