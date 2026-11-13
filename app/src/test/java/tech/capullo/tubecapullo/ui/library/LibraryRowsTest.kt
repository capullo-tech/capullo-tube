package tech.capullo.tubecapullo.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.tubecapullo.data.youtube.YtVideo

/** The History tab's rows: a liked album plays as an album again, everything else as a song. */
class LibraryRowsTest {

    private fun video(id: String, seconds: Long) = YtVideo(id, "Title $id", "Uploader", seconds, null)

    @Test
    fun `history marks liked albums as album rows and keeps the play order`() {
        val history = listOf(video("song", 200), video("album", 2700), video("longSong", 2700))

        val rows = historyRows(history, albumIds = setOf("album"))

        assertEquals(history, rows.map { it.video })
        assertEquals(listOf(false, true, false), rows.map { it.isAlbum })
    }
}
