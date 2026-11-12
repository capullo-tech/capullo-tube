package tech.capullo.tubecapullo.ui.nav

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject

/** The tab shell's now-playing mini-bar, on every tab. */
@HiltViewModel
class ShellViewModel @Inject constructor(
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
) : ViewModel() {

    val playerState = player.state

    /** Liked ids, for the mini-bar's heart. */
    val likedIds = stationEngine.likedIds

    /** Liked album ids: the heart in album mode. */
    val likedAlbumIds = stationEngine.likedAlbumIds

    /** The station, for album mode ([tech.capullo.tubecapullo.station.StationState.albumMode]). */
    val station = stationEngine.state

    /** What each like did to the queue, for the chip above the mini-bar. */
    val likeEffects = stationEngine.likeEffects

    /** A list or album ended and the station goes on as a radio, for the same chip. */
    val rollOvers = stationEngine.rollOvers

    fun togglePlayPause() = player.togglePlayPause()

    fun next() = player.next()

    /** Like/unlike the playing track; in album mode, save or remove the album. */
    fun toggleLike() {
        playerState.value.video?.let(stationEngine::toggleHeart)
    }
}
