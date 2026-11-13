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
    /** The chip. Lives in the ViewModel: it survives rotation and resets to Songs when the app
     *  starts again. */
    val filter: SearchFilter = SearchFilter.SONGS,
    /** Song rows: a Songs search, or a pasted link. */
    val results: List<YtVideo> = emptyList(),
    /** Playlist, album or channel rows from the other filters; [collectionKind] says which. */
    val collections: List<YtCollection> = emptyList(),
    /** What [collections] holds, for the row tap; null while the rows are songs. */
    val collectionKind: CollectionKind? = null,
    /** The query that produced [results]; [query] is the live text field and may have moved on. */
    val resultsQuery: String = "",
    /** Set when [results] are a pasted or shared playlist: the screen offers to play it. */
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

    /** The player's state, for the "playing" marker on the matching row. */
    val playerState = player.state

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast = _toast.asSharedFlow()

    private var searchJob: Job? = null

    init {
        // Service-side events (skipped tracks, station ran dry) surface as toasts. Retry policy
        // moved into PlaybackService with the station loop - the station owns it now.
        viewModelScope.launch { player.events.collect { _toast.tryEmit(it) } }
        // A shared or opened playlist link (links/LinksViewModel) lists here as if pasted.
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

    /** A chip tap: the query in the box runs again under [filter]. A link ignores the filter, so
     *  there is nothing to run again for one. */
    fun onFilterChange(filter: SearchFilter) {
        val state = _uiState.value
        if (filter == state.filter) return
        _uiState.value = state.copy(filter = filter)
        val request = searchRequest(state.query, filter)
        if (request is SearchRequest.Songs || request is SearchRequest.Collections) submit()
    }

    /**
     * One box, four meanings ([searchRequest]): a playlist link lists the whole playlist, a video
     * link lists that one video, anything else is a search under the chip's filter - songs, or
     * playlists, albums or channels. (Same contract as telecloud's search box; here the rows play
     * instead of adding to a station.)
     */
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
        player.start(PlaySource.SongRadio(video)) // the coordinator starts the service
    }

    /** "Radio from results": the first result plays, the other results follow, then the
     *  first result's Mix. The engine
     *  logs the start (`start source=search query=... results=...`). */
    fun playResultsRadio() {
        val state = _uiState.value
        if (state.results.isEmpty()) return
        player.start(PlaySource.SearchRadio(state.resultsQuery, state.results))
    }

    /** A playlist, album or channel row's tap: the list plays as itself, in order. The engine
     *  fetches it and logs the start (`start source=playlist ...` or `source=channel`). */
    fun playCollection(collection: YtCollection) {
        val source = when (_uiState.value.collectionKind ?: return) {
            CollectionKind.CHANNELS ->
                PlaySource.Channel(collection.url, UpNextText.channelName(collection.title))
            CollectionKind.PLAYLISTS, CollectionKind.ALBUMS ->
                PlaySource.Playlist(collection.url, collection.title)
        }
        player.start(source)
    }

    /** The listed playlist as a collection, in order or [shuffle]d. The engine fetches it again
     *  and logs the start (`start source=playlist ...`). */
    fun playPlaylist(shuffle: Boolean) {
        val playlist = _uiState.value.playlist ?: return
        player.start(playlist.copy(shuffle = shuffle))
    }

    /** Pin [video] after the playing track - Play next in a row's menu, shown only while a
     *  station plays. The engine logs the pin (`playNext id=... at=...`). */
    fun playNext(video: YtVideo) {
        stationEngine.playNext(video)
    }

    // YouTubeError already carries listener-readable text; anything else is a surprise, so say so
    // rather than showing an empty message.
    private fun Throwable.readable(): String = when (this) {
        is YouTubeError -> message ?: "YouTube request failed"
        else -> message ?: "Something went wrong"
    }
}
