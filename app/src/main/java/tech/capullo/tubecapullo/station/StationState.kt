package tech.capullo.tubecapullo.station

import tech.capullo.tubecapullo.data.youtube.YtChapter
import tech.capullo.tubecapullo.data.youtube.YtVideo

/** Where a queued track came from. */
enum class Provenance {
    /** The track the listener started a song radio from. */
    SEED,

    /** YouTube's Mix of [Why.fromVideoId]. */
    MIX,

    /** One of the top related videos of [Why.fromVideoId], paired with its Mix. */
    RELATED,

    /** A liked track (Liked, Liked radio). */
    LIKED,

    /** A search result (search radio). */
    SEARCH,

    /** One of a playlist's tracks; [Why.fromTitle] is the playlist's title. */
    PLAYLIST,

    /** One of a channel's uploads; [Why.fromTitle] is the channel's name. */
    CHANNEL,

    /** Play next or a jump: the listener picked it. */
    USER,

    /** The album itself ([PlaySource.Album]); [Why.fromTitle] is its title. */
    ALBUM,
}

/** A collection's repeat setting ([StationEngine.cycleRepeat] steps through them in order). */
enum class RepeatMode {
    /** The list plays once, then rolls into a radio. */
    OFF,

    /** The list starts over (reshuffled when shuffle is on). */
    ALL,

    /** The playing track loops until the listener skips it. */
    ONE,
}

/** Where a track came from: its provenance and, for a Mix, related list, playlist or channel,
 *  what that was. */
data class Why(
    val provenance: Provenance,
    val fromVideoId: String?,
    val fromTitle: String?,
)

data class UpNextItem(val video: YtVideo, val why: Why, val pinned: Boolean)

/** What a like did to the queue, for the Player's chip. Emitted once the liked song's fetch
 *  lands; Save only, Songs added per like = 0 and Liked emit nothing. */
sealed interface LikeEffect {
    val seed: YtVideo

    /** Blend in: [added] tracks of [seed]'s pairs now follow the current song (0: nothing new). */
    data class Blended(override val seed: YtVideo, val added: Int) : LikeEffect

    /** Switch now: the upcoming queue is now [seed]'s pairs, [added] tracks (0: nothing new, the
     *  queue stayed as it was). */
    data class Switched(override val seed: YtVideo, val added: Int) : LikeEffect
}

/** A playlist, channel or album ended and the station goes on as a radio ([from] is the source
 *  that ended): the Player's chip says so as the first radio track starts. */
data class RollOver(val from: PlaySource)

/** An album with chapters while its own video plays: the chapters and the one playing. */
data class AlbumProgress(val chapters: List<YtChapter>, val index: Int)

/** Snapshot of the station for the UI, republished after every engine mutation. */
data class StationState(
    val source: PlaySource? = null,
    val current: YtVideo? = null,
    /** Played this session before [current], oldest first. */
    val earlier: List<YtVideo> = emptyList(),
    /** Every track queued after [current], in play order: the real upcoming list. */
    val queued: List<UpNextItem> = emptyList(),
    /** The source is a finite list ([PlaySource.isCollection]): repeat applies. */
    val isCollection: Boolean = false,
    /** Radios and lists alike ([StationEngine.setShuffle]). */
    val shuffle: Boolean = false,
    /** Lists only; always OFF for a radio. */
    val repeat: RepeatMode = RepeatMode.OFF,
    /** The station's id: its Room row and its Continue card. 0 before the first station. */
    val stationId: Long = 0,
    /** Album mode with chapters, while the album's own video plays; else null. */
    val album: AlbumProgress? = null,
) {
    /** The heart is the album's: an album's own video is playing. */
    val albumMode: Boolean
        get() = source is PlaySource.Album && current?.videoId == source.video.videoId
}
