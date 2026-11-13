package tech.capullo.tubecapullo.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.youtube.CollectionKind
import tech.capullo.tubecapullo.data.youtube.YouTubeError
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YouTubeUrls
import tech.capullo.tubecapullo.data.youtube.YtCollection
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.links.PlaylistLinkRequests
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import tech.capullo.tubecapullo.ui.upnext.UpNextText
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.SONGS,
    val results: List<YtVideo> = emptyList(),
    val collections: List<YtCollection> = emptyList(),
    val collectionKind: CollectionKind? = null,
    val resultsQuery: String = "",
    val playlist: PlaySource.Playlist? = null,
    val isSearching: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val extractor: YouTubeExtractorClient,
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    playlistLinks: PlaylistLinkRequests,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState = _uiState.asStateFlow()

    val playerState = player.state

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast = _toast.asSharedFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch { player.events.collect { _toast.tryEmit(it) } }
        viewModelScope.launch {
            playlistLinks.requests.collect { url ->
                onQueryChange(url)
                submit()
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
    }

    fun onFilterChange(filter: SearchFilter) {
        val state = _uiState.value
        if (filter == state.filter) return
        _uiState.value = state.copy(filter = filter)
        val request = searchRequest(state.query, filter)
        if (request is SearchRequest.Songs || request is SearchRequest.Collections) submit()
    }

    fun submit() {
        val state = _uiState.value
        val request = searchRequest(state.query, state.filter) ?: return
        val query = state.query.trim()

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true, error = null)
            runCatching {
                var songs = emptyList<YtVideo>()
                var collections = emptyList<YtCollection>()
                var playlist: PlaySource.Playlist? = null
                when (request) {
                    is SearchRequest.PlaylistLink -> extractor.playlistInfo(query).let {
                        songs = it.videos
                        playlist = PlaySource.Playlist(
                            YouTubeUrls.playlistUrl(request.playlistId),
                            it.title,
                        )
                    }
                    is SearchRequest.VideoLink -> songs = listOf(extractor.video(request.videoId))
                    is SearchRequest.Songs -> songs = extractor.search(request.query)
                    is SearchRequest.Collections ->
                        collections = extractor.searchCollections(request.query, request.kind)
                }
                _uiState.value = _uiState.value.copy(
                    results = songs,
                    collections = collections,
                    collectionKind = (request as? SearchRequest.Collections)?.kind,
                    resultsQuery = query,
                    playlist = playlist,
                )
                if (songs.isEmpty() && collections.isEmpty()) {
                    _toast.tryEmit("Nothing found for \"$query\"")
                }
            }.onFailure {
                if (it is CancellationException) throw it
                _uiState.value = _uiState.value.copy(error = it.readable())
            }
            _uiState.value = _uiState.value.copy(isSearching = false)
        }
    }

    fun play(video: YtVideo) {
        player.start(PlaySource.SongRadio(video))
    }

    fun playResultsRadio() {
        val state = _uiState.value
        if (state.results.isEmpty()) return
        player.start(PlaySource.SearchRadio(state.resultsQuery, state.results))
    }

    fun playCollection(collection: YtCollection) {
        val source = when (_uiState.value.collectionKind ?: return) {
            CollectionKind.CHANNELS ->
                PlaySource.Channel(collection.url, UpNextText.channelName(collection.title))
            CollectionKind.PLAYLISTS, CollectionKind.ALBUMS ->
                PlaySource.Playlist(collection.url, collection.title)
        }
        player.start(source)
    }

    fun playPlaylist(shuffle: Boolean) {
        val playlist = _uiState.value.playlist ?: return
        player.start(playlist.copy(shuffle = shuffle))
    }

    fun playNext(video: YtVideo) {
        stationEngine.playNext(video)
    }

    private fun Throwable.readable(): String = when (this) {
        is YouTubeError -> message ?: "YouTube request failed"
        else -> message ?: "Something went wrong"
    }
}
