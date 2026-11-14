package tech.capullo.tubecapullo.ui.upnext

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.tubecapullo.data.youtube.YtChapter
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.AlbumProgress
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.Provenance
import tech.capullo.tubecapullo.station.StationState
import tech.capullo.tubecapullo.station.UpNextItem
import tech.capullo.tubecapullo.station.Why

class QueueEntriesTest {
    private fun video(id: String) = YtVideo(id, "Song $id", "Artist", 213, null)

    private fun queued(id: String) = UpNextItem(video(id), Why(Provenance.MIX, "s", "Seed"), pinned = false)

    private fun rows(entries: List<QueueEntry>): List<String> = entries.map {
        when (it) {
            is TrackEntry -> "${it.state} ${it.video.videoId}"
            is ChapterEntry -> "${it.state} chapter ${it.number}"
            is EndLine -> "end"
        }
    }

    @Test
    fun `played rows come first, then the current one, then the upcoming ones`() {
        val state = StationState(
            source = PlaySource.SongRadio(video("a")),
            current = video("c"),
            earlier = listOf(video("a"), video("b")),
            queued = listOf(queued("d"), queued("e")),
        )
        val entries = queueEntries(state)

        assertEquals(
            listOf("PLAYED a", "PLAYED b", "CURRENT c", "UPCOMING d", "UPCOMING e"),
            rows(entries),
        )
        assertEquals(3, initialIndex(entries))
    }

    @Test
    fun `a track both played and upcoming gets a unique key in each place`() {
        val state = StationState(
            current = video("b"),
            earlier = listOf(video("a")),
            queued = listOf(queued("a")),
            isCollection = true,
        )
        val keys = queueEntries(state).map { it.key }

        assertEquals(keys.distinct(), keys)
    }

    @Test
    fun `with nothing upcoming the sheet opens on the current row and says why the list ends`() {
        val radio = StationState(current = video("c"), earlier = listOf(video("a"), video("b")))
        val list = radio.copy(isCollection = true)

        assertEquals(
            listOf("PLAYED a", "PLAYED b", "CURRENT c", "end"),
            rows(queueEntries(radio)),
        )
        assertEquals(2, initialIndex(queueEntries(radio)))
        assertEquals("Looking for the next track", (queueEntries(radio).last() as EndLine).text)
        assertEquals("End of the list", (queueEntries(list).last() as EndLine).text)
    }

    @Test
    fun `an empty station opens at the top`() {
        val entries = queueEntries(StationState())

        assertEquals(listOf("end"), rows(entries))
        assertEquals(0, initialIndex(entries))
    }

    @Test
    fun `album chapters keep their order and take the playing chapter's states`() {
        val album = video("album")
        val chapters = (0 until 4).map { YtChapter("Chapter $it", it * 60_000L) }
        val state = StationState(
            source = PlaySource.Album(album, chapters),
            current = album,
            earlier = listOf(video("a")),
            queued = listOf(queued("r")),
            album = AlbumProgress(chapters, index = 1),
        )
        val entries = queueEntries(state)

        assertEquals(
            listOf(
                "PLAYED a",
                "CURRENT album",
                "PLAYED chapter 1",
                "CURRENT chapter 2",
                "UPCOMING chapter 3",
                "UPCOMING chapter 4",
                "UPCOMING r",
            ),
            rows(entries),
        )
        assertEquals(4, initialIndex(entries))
    }

    @Test
    fun `on an album's last chapter with nothing queued the sheet opens on the album row`() {
        val album = video("album")
        val chapters = (0 until 2).map { YtChapter("Chapter $it", it * 60_000L) }
        val state = StationState(
            source = PlaySource.Album(album, chapters),
            current = album,
            album = AlbumProgress(chapters, index = 1),
        )
        val entries = queueEntries(state)

        assertEquals(
            listOf("CURRENT album", "PLAYED chapter 1", "CURRENT chapter 2", "end"),
            rows(entries),
        )
        assertEquals(0, initialIndex(entries))
    }
}
