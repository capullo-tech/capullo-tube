package tech.capullo.tubecapullo.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.snapcast.SnapcastManager
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject

/** Full-screen player: a thin pass-through to the coordinator, station and snapcast singletons. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    private val snapcastManager: SnapcastManager,
) : ViewModel() {

    val playerState = player.state
    val position = player.position
    val likedIds = stationEngine.likedIds
    val snapcastState = snapcastManager.state
    val localClientId: String get() = snapcastManager.localClientId

    private val http = OkHttpClient()

    /** Cover bytes for the playing video (its YouTube thumbnail); null while loading or on failure. */
    val albumArt: StateFlow<ByteArray?> = player.state
        .map { it.video?.thumbnailUrl }
        .distinctUntilChanged()
        .mapLatest { url -> url?.let { fetch(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private suspend fun fetch(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
        }.getOrNull()
    }

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    fun toggleLike() {
        playerState.value.video?.let { stationEngine.toggleLike(it.videoId) }
    }

    fun setClientVolume(clientId: String, muted: Boolean, percent: Int) = snapcastManager.adjustClientVolume(clientId, percent, muted)

    fun setClientLatency(clientId: String, latencyMs: Int) = snapcastManager.adjustClientLatency(clientId, latencyMs)
}
