package tech.capullo.tubecapullo.data.quantumcast

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioVideosTest {
    private val id = "dQw4w9WgXcQ"
    private val other = "kJQP7kiw5Fk"

    private fun row(youtubeUrl: String?, title: String = "Never Gonna Give You Up", artworkUrl: String? = null) = RadioTrackRow(title, "Rick Astley", artworkUrl, youtubeUrl)

    @Test
    fun `a row with only a search link is skipped`() {
        val rows = listOf(
            row("https://www.youtube.com/results?search_query=Rick+Astley+Never+Gonna+Give+You+Up"),
            row(""),
            row(null),
            row("https://www.youtube.com/watch?v=$id"),
        )

        assertEquals(listOf(id), radioVideos(rows).map { it.videoId })
    }

    @Test
    fun `blank or non-web artwork falls back to the video's YouTube thumbnail`() {
        val third = "9bZkp7q19f0"
        val fourth = "fJ9rUzIMcZQ"
        val videos = radioVideos(
            listOf(
                row("https://www.youtube.com/watch?v=$id", artworkUrl = " "),
                row("https://www.youtube.com/watch?v=$other", artworkUrl = "https://art.example/cover.jpg"),
                row("https://www.youtube.com/watch?v=$third", artworkUrl = "file:///sdcard/cover.jpg"),
                row("https://www.youtube.com/watch?v=$fourth", artworkUrl = "content://media/cover"),
            ),
        )

        assertEquals("https://i.ytimg.com/vi/$id/hqdefault.jpg", videos[0].thumbnailUrl)
        assertEquals("https://art.example/cover.jpg", videos[1].thumbnailUrl)
        assertEquals("https://i.ytimg.com/vi/$third/hqdefault.jpg", videos[2].thumbnailUrl)
        assertEquals("https://i.ytimg.com/vi/$fourth/hqdefault.jpg", videos[3].thumbnailUrl)
    }

    @Test
    fun `provider text is cut to a bounded length`() {
        val long = "x".repeat(MAX_TEXT_CHARS * 10)
        val video = radioVideos(listOf(RadioTrackRow(long, long, null, "https://youtu.be/$id"))).single()

        assertEquals(MAX_TEXT_CHARS, video.title.length)
        assertEquals(MAX_TEXT_CHARS, video.uploader?.length)
    }

    @Test
    fun `a hand-off names its station from the rows, or QuantumCast when none has a name`() {
        val named = listOf(
            RadioTrackRow("a", null, null, "https://www.youtube.com/results?search_query=a", stationName = " "),
            RadioTrackRow("b", null, null, "https://youtu.be/$id", stationName = "FIP"),
        )
        val handOff = radioHandOff(named)

        assertEquals("FIP", handOff.title)
        assertEquals(listOf(id), handOff.videos.map { it.videoId })
        assertEquals(RadioPlaylist.FALLBACK_TITLE, radioHandOff(emptyList()).title)
    }

    @Test
    fun `a video identified twice keeps its first, newest row`() {
        val videos = radioVideos(
            listOf(
                row("https://www.youtube.com/watch?v=$id", title = "newest"),
                row("https://www.youtube.com/watch?v=$other"),
                row("https://youtu.be/$id", title = "older"),
            ),
        )

        assertEquals(listOf(id, other), videos.map { it.videoId })
        assertEquals("newest", videos[0].title)
    }
}
