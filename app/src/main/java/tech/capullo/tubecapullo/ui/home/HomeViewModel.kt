package tech.capullo.tubecapullo.ui.home

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.history.PlayHistory
import tech.capullo.tubecapullo.data.youtube.YouTubeError
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.player.PlaybackService
import tech.capullo.tubecapullo.snapcast.SnapcastManager
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject

data class HomeUiState(
    /** Most-played tracks (M-B) - the "Because you played" section. Empty on first launch. */
    val history: List<YtVideo> = emptyList(),
    val trending: List<YtVideo> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val extractor: YouTubeExtractorClient,
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    snapcastManager: SnapcastManager,
    private val playHistory: PlayHistory,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()

    /** The player's state, exposed for the now-playing bar. */
    val playerState = player.state

    /** Broadcast state (listener list, http port), exposed for the broadcast row. */
    val snapcastState = snapcastManager.state

    /** Liked ids, for the bar's heart state. */
    val likedIds = stationEngine.likedIds

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast = _toast.asSharedFlow()

    init {
        // Service-side events (skipped tracks, station ran dry) surface as toasts, same as search.
        viewModelScope.launch { player.events.collect { _toast.tryEmit(it) } }
        load()
    }

    /**
     * Fresh fetch per launch - the kiosk is a chart snapshot (~0.9 s per fetch, probe
     * 2026-11-10, docs/probes/trending-music-results.md), so there is nothing worth caching.
     * [load] is also the retry path after an error.
     */
    fun load() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val trending = runCatching {
                extractor.trendingMusic(region = null)
            }.onFailure {
                // A newer load cancelled this one: not an error (same rule as SearchViewModel).
                if (it is CancellationException) throw it
                _uiState.value = _uiState.value.copy(error = it.readable())
            }.getOrDefault(emptyList())
            // History failing must not take the trending cards down with it: an empty list just
            // hides the section (see PlayHistory.observeRecent).
            val history = runCatching { playHistory.observeRecent().first() }.getOrDefault(emptyList())
            _uiState.value = _uiState.value.copy(
                trending = trending,
                history = history,
                isLoading = false,
            )
        }
    }

    /** Start a station from [video] - the card-tap action. Mirrors SearchViewModel.play; keep the
     *  two in step (plain startService, not startForegroundService - see that copy's comment). */
    fun play(video: YtVideo) {
        appContext.startService(Intent(appContext, PlaybackService::class.java))
        player.start(PlaySource.SongRadio(video))
    }

    fun togglePlayPause() = player.togglePlayPause()

    fun next() = player.next()

    /** Like/unlike the playing track - boosts its neighbourhood and re-seeds a mix from it. */
    fun toggleLike() {
        playerState.value.video?.let { stationEngine.toggleLike(it) }
    }

    // Same translation as SearchViewModel.readable - YouTubeError already carries
    // listener-readable text (BotCheck included); anything else is a surprise, so say so.
    private fun Throwable.readable(): String = when (this) {
        is YouTubeError -> message ?: "YouTube request failed"
        else -> message ?: "Something went wrong"
    }
}
