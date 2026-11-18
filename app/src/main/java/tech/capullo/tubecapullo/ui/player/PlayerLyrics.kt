package tech.capullo.tubecapullo.ui.player

import tech.capullo.tubecapullo.data.lyrics.Lyrics
import tech.capullo.tubecapullo.data.lyrics.LyricsState

sealed interface PlayerLyrics {
    data object None : PlayerLyrics

    data object Pending : PlayerLyrics

    data class Shown(val lyrics: Lyrics, val offsetMs: Long = 0) : PlayerLyrics

    companion object {

        fun next(previous: PlayerLyrics, videoId: String?, state: LyricsState?): PlayerLyrics {
            val done = (state as? LyricsState.Done)?.takeIf { it.videoId == videoId }
            return when {
                videoId == null -> None
                done != null -> done.lyrics?.let { Shown(it, done.offsetMs) } ?: None
                previous != None -> Pending
                else -> None
            }
        }
    }
}
