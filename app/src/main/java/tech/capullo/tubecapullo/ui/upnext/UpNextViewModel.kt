package tech.capullo.tubecapullo.ui.upnext

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject

@HiltViewModel
class UpNextViewModel @Inject constructor(
    private val stationEngine: StationEngine,
    private val player: PlaybackCoordinator,
) : ViewModel() {
    val station = stationEngine.state
    val likedIds = stationEngine.likedIds

    val likedAlbumIds = stationEngine.likedAlbumIds

    fun playNow(video: YtVideo, again: Boolean) = player.playNow(video, again)

    fun remove(videoId: String) = stationEngine.removeUpcoming(videoId)

    fun toggleLike(video: YtVideo) = stationEngine.toggleLike(video)

    fun toggleHeart(video: YtVideo) = stationEngine.toggleHeart(video)

    fun startRadio(video: YtVideo) = player.start(PlaySource.SongRadio(video))

    fun seekTo(startMs: Long) = player.seekTo(startMs)
}
