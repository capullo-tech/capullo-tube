package tech.capullo.tubecapullo.ui.upnext

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.LikeEffect
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.Provenance
import tech.capullo.tubecapullo.station.Why

class UpNextTextTest {
    private fun video(title: String, uploader: String? = "Rick Astley") = YtVideo("dQw4w9WgXcQ", title, uploader, 213, null)

    @Test
    fun `source title is Radio, Playlist or Channel, then the origin's name`() {
        val seed = video("Rick Astley - Never Gonna Give You Up (Official Music Video)")
        assertEquals(
            SourceTitle("Radio", "Never Gonna Give You Up"),
            UpNextText.sourceTitle(PlaySource.SongRadio(seed)),
        )
        assertEquals(
            SourceTitle("Radio", "\"daft punk\""),
            UpNextText.sourceTitle(PlaySource.SearchRadio("daft punk", emptyList())),
        )
        assertEquals(
            SourceTitle("Radio", "Liked songs"),
            UpNextText.sourceTitle(PlaySource.LikedRadio),
        )
        assertEquals(
            SourceTitle("Playlist", "Liked songs"),
            UpNextText.sourceTitle(PlaySource.Liked(shuffle = true)),
        )
        assertEquals(
            SourceTitle("Playlist", "Discovery"),
            UpNextText.sourceTitle(PlaySource.Playlist("https://youtube.com/x", "Discovery")),
        )
        assertEquals(
            SourceTitle("Channel", "Daft Punk"),
            UpNextText.sourceTitle(PlaySource.Channel("https://youtube.com/c", "Daft Punk")),
        )
    }

    @Test
    fun `provenance says where a track came from`() {
        val track = video("Song")
        val song = "Daft Punk - One More Time (Official Video)"
        fun why(provenance: Provenance, fromTitle: String? = null) = UpNextText.provenance(Why(provenance, "x", fromTitle), track)

        assertEquals("From the Mix of Daft Punk - One More Time", why(Provenance.MIX, song))
        assertEquals("Related to Daft Punk - One More Time", why(Provenance.RELATED, song))
        assertEquals("Your like", why(Provenance.LIKED))
        assertEquals("From your search", why(Provenance.SEARCH))
        assertEquals("From Discovery", why(Provenance.PLAYLIST, "Discovery"))
        assertEquals("You picked this", why(Provenance.USER))
        assertEquals("You picked this", why(Provenance.SEED))
        assertEquals(
            "From Daft Punk",
            UpNextText.provenance(
                Why(Provenance.CHANNEL, null, null),
                video("Song", uploader = "Daft Punk - Topic"),
            ),
        )
    }

    @Test
    fun `provenance without a source title falls back to a generic line`() {
        val track = video("Song", uploader = null)
        fun why(provenance: Provenance) = UpNextText.provenance(Why(provenance, null, null), track)

        assertEquals("From a Mix", why(Provenance.MIX))
        assertEquals("Related", why(Provenance.RELATED))
        assertEquals("From a playlist", why(Provenance.PLAYLIST))
        assertEquals("From the channel", why(Provenance.CHANNEL))
    }

    @Test
    fun `like chip says what the like did to the queue`() {
        val seed = video("Daft Punk - One More Time (Official Video)", uploader = "Daft Punk")
        assertEquals(
            "Added 2 songs like One More Time",
            UpNextText.likeChip(LikeEffect.Blended(seed, added = 2)),
        )
        assertEquals(
            "Added 1 song like One More Time",
            UpNextText.likeChip(LikeEffect.Blended(seed, added = 1)),
        )
        assertEquals(
            "Switching to songs like One More Time",
            UpNextText.likeChip(LikeEffect.Switched(seed, added = 24)),
        )
    }

    @Test
    fun `like chip with nothing added says so in either mode`() {
        val seed = video("Daft Punk - One More Time (Official Video)", uploader = "Daft Punk")
        val nothing = "No new songs like One More Time"
        assertEquals(nothing, UpNextText.likeChip(LikeEffect.Blended(seed, added = 0)))
        assertEquals(nothing, UpNextText.likeChip(LikeEffect.Switched(seed, added = 0)))
    }
}
