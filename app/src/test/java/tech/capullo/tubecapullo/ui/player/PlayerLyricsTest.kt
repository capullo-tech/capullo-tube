package tech.capullo.tubecapullo.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.capullo.tubecapullo.data.lyrics.LyricLine
import tech.capullo.tubecapullo.data.lyrics.Lyrics
import tech.capullo.tubecapullo.data.lyrics.LyricsSource
import tech.capullo.tubecapullo.data.lyrics.LyricsState

/** The Player's lyrics slot across track changes: lyrics only for the playing track, the card
 *  kept while the next lookup runs, and no card for a track without lyrics. */
class PlayerLyricsTest {

    private val lyrics = Lyrics(LyricsSource.LRCLIB_GET, synced = true, lines = listOf(LyricLine(0, "a")))

    @Test
    fun `lyrics show only for the playing track`() {
        assertEquals(
            PlayerLyrics.Shown(lyrics),
            PlayerLyrics.next(PlayerLyrics.None, "A", LyricsState.Done("A", lyrics)),
        )
        // The repository's answer for the track before: not this track's lyrics.
        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.None, "B", LyricsState.Done("A", lyrics)))
        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.Shown(lyrics), null, LyricsState.Done("A", lyrics)))
    }

    @Test
    fun `a shown card stays as pending while the next lookup runs`() {
        val shown = PlayerLyrics.Shown(lyrics)
        assertEquals(PlayerLyrics.Pending, PlayerLyrics.next(shown, "B", LyricsState.Loading("B")))
        assertEquals(PlayerLyrics.Pending, PlayerLyrics.next(shown, "B", LyricsState.Done("A", lyrics)))
        assertEquals(PlayerLyrics.Pending, PlayerLyrics.next(PlayerLyrics.Pending, "C", LyricsState.Loading("C")))
        // No card before: none appears until lyrics are found.
        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.None, "B", LyricsState.Loading("B")))
    }

    @Test
    fun `a track without lyrics has no card`() {
        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.Pending, "B", LyricsState.Done("B", null)))
        assertEquals(PlayerLyrics.None, PlayerLyrics.next(PlayerLyrics.Shown(lyrics), "B", LyricsState.Done("B", null)))
    }
}
