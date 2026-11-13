package tech.capullo.tubecapullo.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cleaner's contract on real-world-shaped titles: packaging goes, version markers stay, the
 * artist prefix goes only when it repeats the uploader, and the result is never blank.
 */
class TitleCleanerTest {

    private fun assertCleans(expected: String, title: String, uploader: String?) = assertEquals("clean(\"$title\", $uploader)", expected, TitleCleaner.clean(title, uploader))

    @Test
    fun `packaging groups are removed`() {
        assertCleans(
            "Never Gonna Give You Up",
            "Rick Astley - Never Gonna Give You Up (Official Music Video)",
            "Rick Astley",
        )
        assertCleans("Perfect", "Ed Sheeran - Perfect [Official Lyric Video]", "Ed Sheeran")
        assertCleans(
            "Blinding Lights",
            "The Weeknd - Blinding Lights (Official Visualizer)",
            "The Weeknd",
        )
        assertCleans("Billie Eilish - bad guy", "Billie Eilish - bad guy (Lyrics)", "7clouds")
        assertCleans("Take On Me", "a-ha - Take On Me (Official Video) [4K]", "a-ha")
        assertCleans(
            "Tití Me Preguntó",
            "Bad Bunny - Tití Me Preguntó (Video Oficial)",
            "Bad Bunny",
        )
        // A noise group mid-title: the words around it close up.
        assertCleans(
            "Get Lucky ft. Pharrell Williams, Nile Rodgers",
            "Daft Punk - Get Lucky (Official Audio) ft. Pharrell Williams, Nile Rodgers",
            "Daft Punk",
        )
    }

    @Test
    fun `nested packaging clears from the inside out`() {
        assertCleans(
            "Wake Me Up",
            "Avicii - Wake Me Up (Official Video (HD))",
            "Avicii - Topic",
        )
    }

    @Test
    fun `version markers stay`() {
        assertCleans(
            "Smells Like Teen Spirit (Live at Reading 1992)",
            "Nirvana - Smells Like Teen Spirit (Live at Reading 1992)",
            "NirvanaVEVO",
        )
        assertCleans(
            "Dreams (2004 Remaster)",
            "Fleetwood Mac - Dreams (2004 Remaster)",
            "Fleetwood Mac",
        )
        // Packaging words next to a version word: the whole group stays, never half-edited.
        assertCleans(
            "Fix You (Official Live Video)",
            "Coldplay - Fix You (Official Live Video)",
            "Coldplay",
        )
        assertCleans(
            "Queen – Bohemian Rhapsody (Official Video Remastered)",
            "Queen – Bohemian Rhapsody (Official Video Remastered)",
            "Queen Official",
        )
        assertCleans(
            "Here Comes The Sun (2019 Mix)",
            "The Beatles - Here Comes The Sun (2019 Mix)",
            "The Beatles",
        )
        // A non-Latin group is not packaging.
        assertCleans(
            "PSY - GANGNAM STYLE(강남스타일) M/V",
            "PSY - GANGNAM STYLE(강남스타일) M/V",
            "officialpsy",
        )
    }

    @Test
    fun `pipe tails are cut`() {
        assertCleans(
            "Kesariya - Full Song",
            "Kesariya - Full Song | Brahmāstra | Ranbir Kapoor | Alia Bhatt | Pritam | " +
                "Arijit Singh",
            "Sony Music India",
        )
        assertCleans(
            "Shape of You",
            "Ed Sheeran - Shape of You (Official Video) | Ed Sheeran",
            "Ed Sheeran",
        )
    }

    @Test
    fun `artist prefix goes only when it repeats the uploader`() {
        // VEVO and " - Topic" suffixes are not part of the artist; spacing and case do not count.
        assertCleans(
            "Anti-Hero",
            "Taylor Swift - Anti-Hero (Official Music Video)",
            "TaylorSwiftVEVO",
        )
        assertCleans(
            "Despacito ft. Daddy Yankee",
            "Luis Fonsi - Despacito ft. Daddy Yankee",
            "LuisFonsiVEVO",
        )
        assertCleans("Midnight City", "M83 - Midnight City", "M83 - Topic")
        // A lyrics channel, a collaboration, no uploader at all: the prefix is the only artist
        // credit on screen, so it stays.
        assertCleans(
            "Lewis Capaldi - Someone You Loved",
            "Lewis Capaldi - Someone You Loved",
            "7clouds",
        )
        assertCleans(
            "Calvin Harris, Dua Lipa - One Kiss",
            "Calvin Harris, Dua Lipa - One Kiss (Official Video)",
            "Calvin Harris",
        )
        assertCleans("Artist - Song", "Artist - Song (HD)", null)
    }

    @Test
    fun `never returns blank`() {
        assertCleans("[Official Music Video]", "[Official Music Video]", "Some Channel")
        assertCleans("(Official Video) | Some Channel", "(Official Video) | Some Channel", null)
        // Artist plus packaging only: the artist survives, the dangling dash does not.
        assertCleans("Rick Astley", "Rick Astley - (Official Video)", "Rick Astley")
    }

    @Test
    fun `the same-song key ignores packaging, case, spaces and punctuation, not versions`() {
        val official = TitleCleaner.key(
            "Rick Astley - Never Gonna Give You Up (Official Music Video)",
            "Rick Astley",
        )
        assertEquals(official, TitleCleaner.key("NEVER GONNA GIVE YOU UP! (Lyrics)", "7clouds"))
        assertNotEquals(
            official,
            TitleCleaner.key("Never Gonna Give You Up (Live at Wembley)", "Rick Astley"),
        )
    }

    @Test
    fun `a title with no letter or digit has no key, so it matches nothing`() {
        // The probe's mathematical-bold "𝐏𝐥𝐚𝐲𝐥𝐢𝐬𝐭" titles are surrogate pairs, not letters.
        assertNull(TitleCleaner.key("𝐏𝐥𝐚𝐲𝐥𝐢𝐬𝐭", "a"))
        assertNull(TitleCleaner.key("✨ ❤️ ✨", "a"))
    }

    private val linkinPark = listOf("Linkin Park")

    @Test
    fun `the album key matches one album's uploads, not the artist's other albums`() {
        val official = TitleCleaner.albumKey("Linkin Park - Hybrid Theory (Full Album)", "Linkin Park", linkinPark)
        // A fan channel's upload: its "LINKIN PARK - " prefix names a liked artist, not the uploader.
        val fan = TitleCleaner.albumKey("LINKIN PARK - HYBRID THEORY [FULL ALBUM] HD", "Rock Classics", linkinPark)
        val reissue = TitleCleaner.albumKey(
            "Hybrid Theory (20th Anniversary Edition) - Full Album",
            "Linkin Park - Topic",
            linkinPark,
        )
        val meteora = TitleCleaner.albumKey("Linkin Park - Meteora (Full Album)", "Linkin Park", linkinPark)

        assertNotNull(official)
        assertEquals(official, fan)
        assertEquals(official, reissue)
        assertNotEquals(official, meteora)
    }

    @Test
    fun `the album key drops Spanish packaging, remasters and years, not a live recording`() {
        val album = TitleCleaner.albumKey("Soda Stereo - Canción Animal (Álbum Completo)", "Soda Stereo", emptyList())
        val remaster = TitleCleaner.albumKey("Canción Animal - Album Completo 1990 Remastered", "Soda Stereo", emptyList())
        val live = TitleCleaner.albumKey("Canción Animal (En Vivo) - Álbum Completo", "Soda Stereo", emptyList())

        assertEquals(album, remaster)
        assertNotEquals(album, live)
    }

    @Test
    fun `the album key keeps a prefix that names neither the uploader nor a liked artist`() {
        val official = TitleCleaner.albumKey("Hybrid Theory (Full Album)", "Linkin Park", linkinPark)
        val unknownArtist = TitleCleaner.albumKey("Linkin Park - Hybrid Theory (Full Album)", "Rock Classics", emptyList())

        assertNotEquals(official, unknownArtist)
    }
}
