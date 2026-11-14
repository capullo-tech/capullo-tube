package tech.capullo.tubecapullo.ui.nav

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
) : ViewModel() {
    val playerState = player.state

    val likedIds = stationEngine.likedIds

    val likedAlbumIds = stationEngine.likedAlbumIds

    val station = stationEngine.state

    val likeEffects = stationEngine.likeEffects

    val rollOvers = stationEngine.rollOvers

    fun togglePlayPause() = player.togglePlayPause()

    fun next() = player.next()

    fun toggleLike() {
        playerState.value.video?.let(stationEngine::toggleHeart)
    }
}
