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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tech.capullo.tubecapullo.data.lyrics.LyricsRepository
import tech.capullo.tubecapullo.data.lyrics.LyricsState
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.snapcast.SnapcastManager
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import tech.capullo.tubecapullo.ui.upnext.UpNextText
import javax.inject.Inject

/**
 * Full-screen player: a thin pass-through to the coordinator and station singletons. Snapcast is
 * read-only here (the multiroom icon's connected count); the controls live in MultiroomSheet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    private val snapcastManager: SnapcastManager,
    private val extractor: YouTubeExtractorClient,
    lyricsRepository: LyricsRepository,
) : ViewModel() {

    val playerState = player.state
    val position = player.position
    val likedIds = stationEngine.likedIds

    /** Liked album ids: the heart in album mode. */
    val likedAlbumIds = stationEngine.likedAlbumIds

    /** The station: the top bar's source, the shuffle state, a collection's repeat state and an
     *  album's chapters. */
    val station = stationEngine.state

    /** What each like did to the queue, for the chip over the art. */
    val likeEffects = stationEngine.likeEffects

    /** A list or album ended and the station goes on as a radio, for the same chip. */
    val rollOvers = stationEngine.rollOvers

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

    /** The lyrics slot under the controls ([PlayerLyrics.next]): the repository looks lyrics up
     *  when a track starts; this pairs its answer with the playing track. */
    val lyrics: StateFlow<PlayerLyrics> = combine(
        player.state.map { it.video?.videoId }.distinctUntilChanged(),
        lyricsRepository.current,
        ::Pair,
    )
        .scan<Pair<String?, LyricsState?>, PlayerLyrics>(PlayerLyrics.None) { previous, pair ->
            PlayerLyrics.next(previous, pair.first, pair.second)
        }
        // Not the seed: when the screen comes back and the flow restarts, a seed would hide
        // the card for a frame and close an open drawer.
        .drop(1)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerLyrics.None)

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs)

    /** The heart: like/unlike the playing track; in album mode, save or remove the album. */
    fun toggleLike() {
        playerState.value.video?.let(stationEngine::toggleHeart)
    }

    /** The playing track's channel as a collection: its uploads, newest first. A track read back
     *  from Room carries no channel URL, so one metadata fetch finds it first. */
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

    /** The song title's "Go to radio" (hidden inside a radio): a song radio
     *  from the playing track replaces the list. The engine logs the start. */
    fun startRadio() {
        playerState.value.video?.let { player.start(PlaySource.SongRadio(it)) }
    }

    fun toggleShuffle() = player.setShuffle(!station.value.shuffle)

    fun cycleRepeat() = player.cycleRepeat()

    private companion object {
        const val TAG = "TubePlayer"
    }
}
