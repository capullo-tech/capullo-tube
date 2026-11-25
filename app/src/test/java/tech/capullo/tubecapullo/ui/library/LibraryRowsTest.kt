package tech.capullo.tubecapullo.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.tubecapullo.data.quantumcast.RadioPlaylist
import tech.capullo.tubecapullo.data.youtube.YtVideo

class LibraryRowsTest {
    private fun video(id: String, seconds: Long) = YtVideo(id, "Title $id", "Uploader", seconds, null)

    @Test
    fun `history marks liked albums as album rows and keeps the play order`() {
        val history = listOf(video("song", 200), video("album", 2700), video("longSong", 2700))

        val rows = historyRows(history, albumIds = setOf("album"))

        assertEquals(history, rows.map { it.video })
        assertEquals(listOf(false, true, false), rows.map { it.isAlbum })
    }

    @Test
    fun `a radio playlist counts its playable songs and names the ones without a YouTube link`() {
        fun counts(playable: Int, total: Int) = radioPlaylistCounts(RadioPlaylist(1, "FIP", "FR", null, total, playable, 0))

        assertEquals("12 songs", counts(playable = 12, total = 12))
        assertEquals("1 song · 3 without a YouTube link", counts(playable = 1, total = 4))
        assertEquals("3 without a YouTube link", counts(playable = 0, total = 3))
    }
}
