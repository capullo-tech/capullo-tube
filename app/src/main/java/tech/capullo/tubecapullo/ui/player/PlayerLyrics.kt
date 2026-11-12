package tech.capullo.tubecapullo.ui.player

import tech.capullo.tubecapullo.data.lyrics.Lyrics
import tech.capullo.tubecapullo.data.lyrics.LyricsState

/**
 * The Player's lyrics slot: no card ([None]), the card while the next track's lookup runs
 * ([Pending]), or the card with the playing track's lyrics ([Shown]).
 */
sealed interface PlayerLyrics {
    data object None : PlayerLyrics

    data object Pending : PlayerLyrics

    data class Shown(val lyrics: Lyrics) : PlayerLyrics

    companion object {
        /**
         * The slot for [videoId] (the playing track) once the repository says [state]. Lyrics
         * show only for the playing track. While a lookup runs, a card that was showing stays
         * as [Pending], so a run of songs with lyrics does not move the Player twice per track;
         * with no card before, none appears until lyrics are found. No lyrics: no card.
         */
        fun next(previous: PlayerLyrics, videoId: String?, state: LyricsState?): PlayerLyrics {
            val done = (state as? LyricsState.Done)?.takeIf { it.videoId == videoId }
            return when {
                videoId == null -> None
                done != null -> done.lyrics?.let(::Shown) ?: None
                previous != None -> Pending
                else -> None
            }
        }
    }
}
