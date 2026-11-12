package tech.capullo.tubecapullo.station

import tech.capullo.tubecapullo.data.youtube.YtChapter
import tech.capullo.tubecapullo.data.youtube.YtVideo

/** What a station plays from. [StationEngine.start] turns one of these into a queue; see its KDoc
 *  for how each source fills and grows.
 *
 *  Three kinds. Radios are endless: pairs of YouTube's Mix and related videos, chained from the
 *  last song played (Liked radio plays each like followed by songs like it). Collections
 *  ([isCollection]) are finite ordered lists - Liked, a playlist, a channel's uploads - played in
 *  order or shuffled, with repeat. An album is one long video whose chapters are its tracks. */
sealed interface PlaySource {
    /** True for the finite lists that repeat applies to. */
    val isCollection: Boolean get() = this is Liked || this is Playlist || this is Channel

    /** Endless: [seed] first, then its pairs (Mix and related), then the pairs of the last song
     *  played, and so on. */
    data class SongRadio(val seed: YtVideo) : PlaySource

    /** Endless: the first of [results] plays first, the other results follow, then the first
     *  result's pairs and the same chain as [SongRadio]. */
    data class SearchRadio(val query: String, val results: List<YtVideo>) : PlaySource

    /** Finite: the YouTube playlist at [url] (a Mix included) in its own order; [title] names it
     *  in the Player. The engine replaces [title] with YouTube's own name once the list lands.
     *  [shuffle] starts it shuffled. Rolls into a song radio from its last track. */
    data class Playlist(
        val url: String,
        val title: String,
        val shuffle: Boolean = false,
    ) : PlaySource {
        companion object {
            /** A Mix's title until YouTube's name for it lands. Neutral on purpose: the name
             *  the Player keeps is YouTube's, so the app does not invent one. */
            const val MIX_PLACEHOLDER = "YouTube Mix"
        }
    }

    /** Finite: the uploads of the channel at [url], newest first; [name] names it in the Player.
     *  A channel, not an artist: YouTube's uploader is often a label ("Mazzika", "Rotana").
     *  Rolls into a song radio from its last track. */
    data class Channel(val url: String, val name: String) : PlaySource

    /** Finite: every liked track, newest like first or shuffled; rolls into [LikedRadio]. */
    data class Liked(val shuffle: Boolean) : PlaySource

    /** Endless: likes in rounds (shuffled by default, else newest first), no like repeated within
     *  a round, each followed by `AppSettings.songsPerLike` tracks of its pairs. */
    data object LikedRadio : PlaySource

    /** One long video played as an album (PLAN "Home v2 and albums"): its YouTube [chapters]
     *  drive Next and Previous and list in Up next; with none it plays as one track. A start may
     *  pass no chapters: the engine fetches them. Its end rolls into a radio from [video]. */
    data class Album(val video: YtVideo, val chapters: List<YtChapter> = emptyList()) : PlaySource
}
