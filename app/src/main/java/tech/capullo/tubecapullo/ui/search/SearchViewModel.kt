package tech.capullo.tubecapullo.ui.search

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.youtube.YouTubeError
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YouTubeUrls
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.player.PlaybackService
import tech.capullo.tubecapullo.snapcast.SnapcastManager
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: List<YtVideo> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val extractor: YouTubeExtractorClient,
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    snapcastManager: SnapcastManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState = _uiState.asStateFlow()

    /** The player's state, exposed for the now-playing bar. */
    val playerState = player.state

    /** Broadcast state (listener list, http port), exposed for the broadcast row. */
    val snapcastState = snapcastManager.state

    /** Liked ids, for the bar's heart state. */
    val likedIds = stationEngine.likedIds

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast = _toast.asSharedFlow()

    private var searchJob: Job? = null

    init {
        // Service-side events (skipped tracks, station ran dry) surface as toasts. Retry policy
        // moved into PlaybackService with the station loop - the station owns it now.
        viewModelScope.launch { player.events.collect { _toast.tryEmit(it) } }
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    /**
     * One box, three meanings: a playlist link lists the whole playlist, a video link lists that
     * one video, anything else is a search. Playlist wins over video for a watch URL carrying
     * `&list=` - pasting a song "from" a playlist usually means the playlist. (Same contract as
     * telecloud's search box; here the rows play instead of adding to a station.)
     */
    fun submit() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, error = null)
            runCatching {
                val results = when {
                    YouTubeUrls.isPlaylist(query) -> extractor.playlistInfo(query).videos
                    YouTubeUrls.parseVideoId(query) != null ->
                        listOf(extractor.video(YouTubeUrls.parseVideoId(query)!!))
                    else -> extractor.search(query)
                }
                _uiState.value = _uiState.value.copy(results = results)
                if (results.isEmpty()) _toast.tryEmit("Nothing found for \"$query\"")
            }.onFailure {
                // A newer submit cancelled this search: not an error, and the newer job owns the
                // spinner. runCatching catches CancellationException, so rethrow it here.
                if (it is CancellationException) throw it
                _uiState.value = _uiState.value.copy(error = it.readable())
            }
            _uiState.value = _uiState.value.copy(isSearching = false)
        }
    }

    /** Start a station from [video] - the tap-a-row action. Resolution happens in the service's
     *  station loop; the UI just names the seed. */
    fun play(video: YtVideo) {
        // Plain startService, NOT startForegroundService: the FGS variant demands startForeground()
        // within 5s, but stream resolution alone can take longer and MediaSessionService only
        // promotes itself once playback starts. A plain start from a foreground app is allowed and
        // has no deadline; the coordinator's replay=1 bridges the service's cold-start race.
        appContext.startService(Intent(appContext, PlaybackService::class.java))
        player.start(PlaySource.SongRadio(video))
    }

    fun togglePlayPause() = player.togglePlayPause()

    fun next() = player.next()

    /** Like/unlike the playing track - boosts its neighbourhood and re-seeds a mix from it. */
    fun toggleLike() {
        playerState.value.video?.let { stationEngine.toggleLike(it) }
    }

    // YouTubeError already carries listener-readable text; anything else is a surprise, so say so
    // rather than showing an empty message.
    private fun Throwable.readable(): String = when (this) {
        is YouTubeError -> message ?: "YouTube request failed"
        else -> message ?: "Something went wrong"
    }
}
