package tech.capullo.tubecapullo.ui.upnext

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject

/** The Queue sheet: a pass-through to the station. Each action logs a TubeStation line in the
 *  engine (jump, remove, like, start), so the sheet adds no logging of its own. */
@HiltViewModel
class UpNextViewModel @Inject constructor(
    private val stationEngine: StationEngine,
    private val player: PlaybackCoordinator,
) : ViewModel() {

    val station = stationEngine.state
    val likedIds = stationEngine.likedIds

    /** Liked album ids: the current row's heart in album mode. */
    val likedAlbumIds = stationEngine.likedAlbumIds

    /** A row tap: [video] plays now; [again] for a played row (it plays again, then the station
     *  goes on). */
    fun playNow(video: YtVideo, again: Boolean) = player.playNow(video, again)

    fun remove(videoId: String) = stationEngine.removeUpcoming(videoId)

    fun toggleLike(video: YtVideo) = stationEngine.toggleLike(video)

    /** The current row's heart, the same as the Player's: in album mode it saves the album. */
    fun toggleHeart(video: YtVideo) = stationEngine.toggleHeart(video)

    /** A collection row's "Go to radio": a song radio replaces the list. The
     *  engine logs the start (`start source=song ...`). */
    fun startRadio(video: YtVideo) = player.start(PlaySource.SongRadio(video))

    /** A chapter row's tap: the album seeks to [startMs]; the engine follows the chapter from
     *  the playhead (`album chapter at=...`). */
    fun seekTo(startMs: Long) = player.seekTo(startMs)
}
