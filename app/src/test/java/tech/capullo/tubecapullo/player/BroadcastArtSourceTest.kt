package tech.capullo.tubecapullo.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.tubecapullo.data.youtube.YtVideo

class BroadcastArtSourceTest {
    private val id = "dQw4w9WgXcQ"
    private val maxres = "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
    private val hq = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    private fun video(thumbnailUrl: String?) = YtVideo(id, "Never Gonna Give You Up", "Rick Astley", 213, thumbnailUrl)

    @Test
    fun `maxres is tried first, then the extractor's thumbnail, then hqdefault`() {
        val extractor = "https://i.ytimg.com/vi/$id/hqdefault.jpg?sqp=-oaymwEcCNACELwBSFXyq4qpAw4IARUAAIhCGAFwAcABBg"

        val source = BroadcastArtSource.of(video(extractor))

        assertEquals(listOf(maxres, extractor, hq), source.downloadUrls)
        assertEquals(extractor, source.directUrl)
    }

    @Test
    fun `without an extractor thumbnail hqdefault is announced, since every video has one`() {
        val source = BroadcastArtSource.of(video(null))

        assertEquals(listOf(maxres, hq), source.downloadUrls)
        assertEquals(hq, source.directUrl)
    }

    @Test
    fun `an extractor thumbnail that already is maxres or hqdefault is not downloaded twice`() {
        assertEquals(listOf(maxres, hq), BroadcastArtSource.of(video(maxres)).downloadUrls)
        assertEquals(listOf(maxres, hq), BroadcastArtSource.of(video(hq)).downloadUrls)
    }
}
