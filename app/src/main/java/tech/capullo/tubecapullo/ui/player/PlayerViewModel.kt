package tech.capullo.tubecapullo.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.snapcast.SnapcastManager
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import tech.capullo.tubecapullo.ui.upnext.UpNextText
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    private val snapcastManager: SnapcastManager,
    private val extractor: YouTubeExtractorClient,
) : ViewModel() {
    val playerState = player.state
    val position = player.position
    val likedIds = stationEngine.likedIds

    val likedAlbumIds = stationEngine.likedAlbumIds

    val station = stationEngine.state

    val likeEffects = stationEngine.likeEffects

    val rollOvers = stationEngine.rollOvers

    val snapcastState = snapcastManager.state
    val localClientId: String get() = snapcastManager.localClientId

    private val http = OkHttpClient()

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
        playerState.value.video?.let(stationEngine::toggleHeart)
    }

    fun startChannel(video: YtVideo) {
        val uploader = video.uploader ?: return
        viewModelScope.launch {
            val url = video.uploaderUrl ?: try {
                extractor.video(video.videoId).uploaderUrl
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "channel id=${video.videoId} result=error error=${e.message}")
                null
            }
            if (url == null) {
                Log.w(TAG, "channel id=${video.videoId} result=idle reason=no-channel-url")
                return@launch
            }
            player.start(PlaySource.Channel(url, UpNextText.channelName(uploader)))
        }
    }

    fun startRadio() {
        playerState.value.video?.let { player.start(PlaySource.SongRadio(it)) }
    }

    fun toggleShuffle() = player.setShuffle(!station.value.shuffle)

    fun cycleRepeat() = player.cycleRepeat()

    private companion object {
        const val TAG = "TubePlayer"
    }
}
