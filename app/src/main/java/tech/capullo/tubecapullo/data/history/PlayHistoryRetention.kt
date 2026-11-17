package tech.capullo.tubecapullo.data.history

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayHistoryRetention @Inject constructor(
    private val playHistory: PlayHistory,
    private val settings: AppSettings,
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            settings.historyRetention.collect { playHistory.prune(it, playingId()) }
        }
    }

    fun clear() {
        stationEngine.forgetAllStations()
        scope.launch { playHistory.clear(playingId()) }
    }

    private fun playingId(): String? = player.state.value.video?.videoId
}
