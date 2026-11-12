package tech.capullo.tubecapullo.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tech.capullo.tubecapullo.data.history.LikedAlbums
import tech.capullo.tubecapullo.data.history.LikedTracks
import tech.capullo.tubecapullo.data.history.PlayHistory
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject

/**
 * Keeps the Room observers alive this long after the Library stops collecting, so a rotation
 * (activity recreate, well under a second) re-subscribes to a warm flow instead of flashing the
 * loading state. 5 s is the Android architecture guidance value, not measured here (GUESS for
 * this app). Shorter risks a reload on slow recreates; longer only holds a cheap Room query.
 */
private const val STOP_TIMEOUT_MS = 5_000L

/** A Library row: [video], played as an album when [isAlbum], else as a song radio. */
data class LibraryRow(val video: YtVideo, val isAlbum: Boolean)

/**
 * The History tab's rows from the recent plays ([history], most recent first): a liked album
 * ([albumIds]) is an album row, so a tap plays it as an album again; everything else is a song.
 */
internal fun historyRows(history: List<YtVideo>, albumIds: Set<String>): List<LibraryRow> = history.map { LibraryRow(it, it.videoId in albumIds) }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    likedTracks: LikedTracks,
    likedAlbums: LikedAlbums,
    playHistory: PlayHistory,
) : ViewModel() {

    /** Liked songs, newest like first (never an album: albums have their own table). Null until
     *  Room's first emission (loading). */
    val liked: StateFlow<List<YtVideo>?> = likedTracks.observe()
        .stateIn<List<YtVideo>?>(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            null,
        )

    /** Liked albums, newest like first. Null until Room's first emission (loading). */
    val albums: StateFlow<List<YtVideo>?> = likedAlbums.observeEntries()
        .map { entries -> entries.map { it.video } }
        .stateIn<List<YtVideo>?>(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            null,
        )

    /** Recently played, most recent first, liked albums marked ([historyRows]). Null until
     *  Room's first emission (loading). */
    val history: StateFlow<List<LibraryRow>?> = combine(
        playHistory.observeRecent(),
        stationEngine.likedAlbumIds,
        ::historyRows,
    ).stateIn<List<LibraryRow>?>(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        null,
    )

    /** The player's state, for highlighting the playing row. */
    val playerState = player.state

    /** Liked mode: the finite queue of likes, in order or shuffled. */
    fun playLiked(shuffle: Boolean) = player.start(PlaySource.Liked(shuffle))

    fun playLikedRadio() = player.start(PlaySource.LikedRadio)

    /** The row-tap action, same as a track tap anywhere else: a song radio seeded by [video]. */
    fun playSongRadio(video: YtVideo) = player.start(PlaySource.SongRadio(video))

    /** An album row's tap: the video plays as an album (its chapters are fetched first). */
    fun playAlbum(video: YtVideo) = player.start(PlaySource.Album(video))

    /** A row menu's Play next, shown only while a station plays. */
    fun playNext(video: YtVideo) = stationEngine.playNext(video)

    /** Only called from the Songs list, so this always unlikes; the row then drops out of it. */
    fun unlike(video: YtVideo) = stationEngine.toggleLike(video)

    /** Only called from the Albums list: removes the album; the row then drops out of it. */
    fun unlikeAlbum(album: YtVideo) = stationEngine.toggleAlbumLike(album)
}
