package tech.capullo.tubecapullo.station

import tech.capullo.tubecapullo.data.youtube.YtChapter
import tech.capullo.tubecapullo.data.youtube.YtVideo

enum class Provenance {
    SEED,

    MIX,

    RELATED,

    LIKED,

    SEARCH,

    PLAYLIST,

    CHANNEL,

    USER,

    ALBUM,
}

enum class RepeatMode {
    OFF,

    ALL,

    ONE,
}

data class Why(
    val provenance: Provenance,
    val fromVideoId: String?,
    val fromTitle: String?,
)

data class UpNextItem(val video: YtVideo, val why: Why, val pinned: Boolean)

sealed interface LikeEffect {
    val seed: YtVideo

    data class Blended(override val seed: YtVideo, val added: Int) : LikeEffect

    data class Switched(override val seed: YtVideo, val added: Int) : LikeEffect
}

data class RollOver(val from: PlaySource)

data class AlbumProgress(val chapters: List<YtChapter>, val index: Int)

data class StationState(
    val source: PlaySource? = null,
    val current: YtVideo? = null,
    val earlier: List<YtVideo> = emptyList(),
    val queued: List<UpNextItem> = emptyList(),
    val isCollection: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val stationId: Long = 0,
    val album: AlbumProgress? = null,
) {
    val albumMode: Boolean
        get() = source is PlaySource.Album && current?.videoId == source.video.videoId
}
