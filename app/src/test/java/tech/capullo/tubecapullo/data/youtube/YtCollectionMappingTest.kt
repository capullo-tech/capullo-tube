package tech.capullo.tubecapullo.data.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem

class YtCollectionMappingTest {
    private val youtube = ServiceList.YouTube.serviceId

    private fun album(url: String, count: Long, uploader: String? = "Daft Punk") = PlaylistInfoItem(youtube, url, "Discovery").apply {
        streamCount = count
        uploaderName = uploader
        thumbnails = listOf(
            Image("https://img/small", 60, 60, Image.ResolutionLevel.LOW),
            Image("https://img/large", 544, 544, Image.ResolutionLevel.HIGH),
        )
    }

    @Test
    fun `a YouTube Music album maps to the plain playlist link with its art and artist`() {
        val music = "https://music.youtube.com/playlist?list=OLAK5uy_mz6eafmqdRHSaR4IwG0ll6J6rgv0_ZpGw"

        val row = album(music, ListExtractor.ITEM_COUNT_UNKNOWN).toYtCollection()

        assertEquals(
            YtCollection(
                url = "https://www.youtube.com/playlist?list=OLAK5uy_mz6eafmqdRHSaR4IwG0ll6J6rgv0_ZpGw",
                title = "Discovery",
                uploader = "Daft Punk",
                thumbnailUrl = "https://img/large",
                itemCount = null,
            ),
            row,
        )
    }

    @Test
    fun `only a real count survives - unknown, endless and more-than-100 do not`() {
        val url = "https://www.youtube.com/playlist?list=PLSdoVPM5WnndLX6Ngmb8wktMF61dJirKl"
        assertEquals(14L, album(url, 14).toYtCollection()?.itemCount)
        assertEquals(0L, album(url, 0).toYtCollection()?.itemCount)
        listOf(
            ListExtractor.ITEM_COUNT_UNKNOWN,
            ListExtractor.ITEM_COUNT_INFINITE,
            ListExtractor.ITEM_COUNT_MORE_THAN_100,
        ).forEach { assertNull(album(url, it).toYtCollection()?.itemCount) }
    }

    @Test
    fun `a playlist item without a playlist id is dropped`() {
        assertNull(album("https://www.youtube.com/watch?v=dQw4w9WgXcQ", 3).toYtCollection())
    }

    @Test
    fun `a channel keeps its page and name and has no uploader`() {
        val url = "https://www.youtube.com/channel/UC_kRDKYrUlrbtrSiyu5Tflg"
        val channel = ChannelInfoItem(youtube, url, "Daft Punk").apply {
            streamCount = ListExtractor.ITEM_COUNT_UNKNOWN
        }

        assertEquals(YtCollection(url, "Daft Punk", null, null, null), channel.toYtCollection())
        assertNull(ChannelInfoItem(youtube, url, " ").toYtCollection())
    }
}
