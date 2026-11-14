package tech.capullo.tubecapullo.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.history.LikedAlbums
import tech.capullo.tubecapullo.data.history.LikedTracks
import tech.capullo.tubecapullo.data.history.PlayHistory
import tech.capullo.tubecapullo.data.history.StationSnapshots
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.youtube.YouTubeError
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.data.youtube.defaultChartRegion
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import java.util.Locale
import javax.inject.Inject
import kotlin.random.Random

data class HomeUiState(
    val continueCards: List<ContinueCard> = emptyList(),
    val listenAgain: HomeSection.ListenAgain? = null,
    val moreLike: List<HomeSection.BecauseLiked> = emptyList(),
    val fullAlbums: List<YtVideo> = emptyList(),
    val trending: List<TrendingFetch> = emptyList(),
    val isLoading: Boolean = true,
) {
    val sections: List<HomeSection> = homeSections(continueCards, listenAgain, moreLike, fullAlbums, trending)

    val error: String? = trendingError(trending)
}

@OptIn(FlowPreview::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val extractor: YouTubeExtractorClient,
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    private val playHistory: PlayHistory,
    private val likedTracks: LikedTracks,
    private val likedAlbums: LikedAlbums,
    private val snapshots: StationSnapshots,
    private val random: Random,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()

    val playerState = player.state

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast = _toast.asSharedFlow()

    private val listenAgainDraw = MutableStateFlow(ListenAgainDraw(random.nextInt(), System.currentTimeMillis()))

    private var loadJob: Job? = null
    private var moreLikeJob: Job? = null
    private var fullAlbumsJob: Job? = null
    private var trendingJob: Job? = null

    init {
        viewModelScope.launch { player.events.collect { _toast.tryEmit(it) } }
        load()
        viewModelScope.launch {
            appSettings.chartRegions.drop(1).collect {
                Log.i(TAG, "reload rows=trending reason=charts regions=$it")
                reloadTrending()
            }
        }
        viewModelScope.launch {
            appSettings.moreLikeRows.drop(1).collect {
                Log.i(TAG, "reload rows=more-like reason=setting count=$it")
                reloadMoreLike()
            }
        }
        viewModelScope.launch {
            appSettings.fullAlbums.drop(1).collect {
                Log.i(TAG, "reload rows=full-albums reason=setting on=$it")
                reloadFullAlbums()
            }
        }
        viewModelScope.launch {
            likedTracks.observe().drop(1).debounce(LIKES_RELOAD_DEBOUNCE_MS).collect {
                Log.i(TAG, "reload rows=more-like,full-albums reason=likes likes=${it.size}")
                reloadLikeRows()
            }
        }
        viewModelScope.launch {
            likedAlbums.observeEntries().drop(1).debounce(LIKES_RELOAD_DEBOUNCE_MS).collect {
                Log.i(TAG, "reload rows=full-albums reason=album-likes albums=${it.size}")
                reloadFullAlbums()
            }
        }
        val playingStation = stationEngine.state.map { it.stationId }.distinctUntilChanged()
        val continueFlow = combine(snapshots.observe(), playingStation, appSettings.continueCards) { stations, playing, max ->
            continueCards(stations, playing, max)
        }
        viewModelScope.launch {
            continueFlow.collect { cards -> _uiState.update { it.copy(continueCards = cards) } }
        }
        viewModelScope.launch {
            combine(
                likedTracks.observeEntries(),
                likedAlbums.observeEntries(),
                playHistory.observeListenAgainPool(LISTEN_AGAIN_MIN_PLAYS),
                player.state.map { it.video?.videoId }.distinctUntilChanged(),
                combine(continueFlow, listenAgainDraw) { cards, draw -> continueIds(cards) to draw },
            ) { songs, albums, plays, playingId, (continueIds, draw) ->
                listenAgainCards(songs, albums, plays, draw.nowEpochMs, playingId, continueIds, draw.seed)
            }.collect { row -> _uiState.update { it.copy(listenAgain = row) } }
        }
    }

    fun load() {
        loadJob?.cancel()
        listOf(moreLikeJob, fullAlbumsJob, trendingJob).forEach { it?.cancel() }
        listenAgainDraw.value = ListenAgainDraw(random.nextInt(), System.currentTimeMillis())
        _uiState.update { state ->
            state.copy(isLoading = true, trending = state.trending.filter { it.error == null })
        }
        loadJob = viewModelScope.launch {
            val related = RelatedFetches(this)
            val trending = async { trendingFetches() }
            val moreLike = async { orEmpty { moreLikeRows(related) } }
            val albums = async { orEmpty { fullAlbumsRow(related) } }
            val rows = moreLike.await()
            val albumCards = albums.await()
            val charts = trending.await()
            _uiState.update {
                it.copy(moreLike = rows, fullAlbums = albumCards, trending = charts, isLoading = false)
            }
        }
    }

    private fun reloadLikeRows() {
        val related = RelatedFetches(viewModelScope)
        reloadMoreLike(related)
        reloadFullAlbums(related)
    }

    private fun reloadMoreLike(related: RelatedFetches? = null) {
        moreLikeJob = reloadPart(moreLikeJob) {
            val rows = orEmpty { coroutineScope { moreLikeRows(related ?: RelatedFetches(this)) } }
            _uiState.update { it.copy(moreLike = rows) }
        }
    }

    private fun reloadFullAlbums(related: RelatedFetches? = null) {
        fullAlbumsJob = reloadPart(fullAlbumsJob) {
            val cards = orEmpty { coroutineScope { fullAlbumsRow(related ?: RelatedFetches(this)) } }
            _uiState.update { it.copy(fullAlbums = cards) }
        }
    }

    private fun reloadTrending() {
        trendingJob = reloadPart(trendingJob) {
            val fetches = trendingFetches()
            _uiState.update { it.copy(trending = fetches) }
        }
    }

    private fun reloadPart(previous: Job?, fetchAndApply: suspend () -> Unit): Job {
        previous?.cancel()
        return viewModelScope.launch {
            loadJob?.join()
            fetchAndApply()
        }
    }

    private suspend fun trendingFetches(): List<TrendingFetch> = coroutineScope {
        val regions = appSettings.chartRegions.value.ifEmpty {
            listOf(
                defaultChartRegion(
                    Locale.getDefault().country,
                    extractor.supportedRegions().toSet(),
                ),
            )
        }
        regions.map { region ->
            async {
                runCatching { extractor.trendingMusic(region) }.fold(
                    onSuccess = { TrendingFetch(region, it) },
                    onFailure = {
                        if (it is CancellationException) throw it
                        Log.w(
                            TAG,
                            "trending region=$region result=failed " +
                                "error=${it.message}",
                        )
                        TrendingFetch(region, emptyList(), it.readable())
                    },
                )
            }
        }.awaitAll()
    }

    private suspend fun moreLikeRows(related: RelatedFetches): List<HomeSection.BecauseLiked> = coroutineScope {
        val rows = appSettings.moreLikeRows.value
        if (rows == 0) return@coroutineScope emptyList()
        val likes = likedTracks.all()
        val likedIds = likes.mapTo(HashSet()) { it.videoId }
        likes.take(rows).map { seed ->
            async {
                val mix = async { fetchOrNull("moreLike", "mix", seed) { extractor.mix(seed.videoId) } }
                val relatedVideos = related.of(seed, "moreLike")
                val mixVideos = mix.await()
                if (mixVideos == null && relatedVideos == null) return@async null
                val cards = becauseLikedCards(
                    seed,
                    mixVideos.orEmpty(),
                    relatedVideos ?: YtRelated(seedViews = -1, videos = emptyList()),
                    likedIds,
                    random,
                )
                Log.i(
                    TAG,
                    "moreLike seed=${seed.videoId} mix=${mixVideos?.size ?: "failed"} " +
                        "related=${relatedVideos?.videos?.size ?: "failed"} cards=${cards.size}",
                )
                HomeSection.BecauseLiked(seed, cards).takeIf { cards.isNotEmpty() }
            }
        }.awaitAll().filterNotNull()
    }

    private suspend fun fullAlbumsRow(related: RelatedFetches): List<YtVideo> = coroutineScope {
        if (!appSettings.fullAlbums.value) return@coroutineScope emptyList()
        val likes = likedTracks.all()
        if (likes.isEmpty()) return@coroutineScope emptyList()
        val excluded = likes.mapTo(HashSet()) { it.videoId }
        likedAlbums.all().mapTo(excluded) { it.videoId }
        val artists = likes.mapNotNullTo(LinkedHashSet()) { it.uploader }
        val seeds = likes.take(FULL_ALBUM_SEEDS)
        val fetches = seeds.map { async { related.of(it, "fullAlbums") } }.awaitAll().filterNotNull()
        val candidates = fullAlbumCandidates(fetches, excluded)
        val checks = chapterChecks(candidates, FULL_ALBUM_CHAPTER_CHECKS)
        val chaptered = chapteredIds(checks)
        val albums = albumsOf(candidates, chaptered)
        var row = fullAlbumCards(albums, artists)
        var fallback = "-"
        if (row.cards.size < FULL_ALBUMS_MIN_CARDS) {
            val searched = fullAlbumArtists(likes)
            fallback = searched.joinToString(",") { "\"$it\"" }.ifEmpty { "none" }
            val searches = searched.map { artist ->
                async {
                    fetchOrNull("fullAlbums", "search", null) { extractor.search("$artist full album") }
                }
            }.awaitAll().filterNotNull()
            val more = fullAlbumCandidates(
                searches.map { YtRelated(seedViews = -1, videos = it) },
                excluded + albums.map { it.videoId },
            )
            val moreChaptered = chapteredIds(chapterChecks(more, FULL_ALBUM_CHAPTER_CHECKS - checks.size))
            row = fullAlbumCards(albums + albumsOf(more, moreChaptered), artists)
        }
        Log.i(
            TAG,
            "fullAlbums seeds=${seeds.size} fetched=${fetches.size} candidates=${candidates.size} " +
                "checked=${checks.size} chaptered=${chaptered.size} cards=${row.cards.size} " +
                "dupes=${row.dupes} fallback=$fallback",
        )
        row.cards
    }

    private suspend fun chapteredIds(videos: List<YtVideo>): Set<String> = coroutineScope {
        videos.map { video ->
            async {
                fetchOrNull("fullAlbums", "chapters", video) { extractor.album(video.videoId) }
                    ?.takeIf { it.chapters.isNotEmpty() }?.video?.videoId
            }
        }.awaitAll().filterNotNullTo(HashSet())
    }

    private suspend fun <T> fetchOrNull(
        row: String,
        what: String,
        seed: YtVideo?,
        fetch: suspend () -> T,
    ): T? = runCatching { fetch() }.getOrElse {
        if (it is CancellationException) throw it
        Log.w(TAG, "$row seed=${seed?.videoId ?: "-"} $what=failed error=${it.message}")
        null
    }

    private inner class RelatedFetches(private val scope: CoroutineScope) {
        private val fetches = HashMap<String, Deferred<YtRelated?>>()

        suspend fun of(seed: YtVideo, row: String): YtRelated? = synchronized(fetches) {
            fetches.getOrPut(seed.videoId) {
                scope.async { fetchOrNull(row, "related", seed) { extractor.relatedStreams(seed.videoId) } }
            }
        }.await()
    }

    fun play(video: YtVideo) {
        player.start(PlaySource.SongRadio(video))
    }

    fun playAlbum(video: YtVideo) {
        player.start(PlaySource.Album(video))
    }

    fun resume(card: ContinueCard) {
        player.resume(card.stationId)
    }

    fun playNext(video: YtVideo) = stationEngine.playNext(video)

    private suspend fun <T> orEmpty(block: suspend () -> List<T>): List<T> = runCatching {
        block()
    }.getOrElse {
        if (it is CancellationException) throw it
        Log.w(TAG, "home section read failed", it)
        emptyList()
    }

    private fun Throwable.readable(): String = when (this) {
        is YouTubeError -> message ?: "YouTube request failed"
        else -> message ?: "Something went wrong"
    }

    private data class ListenAgainDraw(val seed: Int, val nowEpochMs: Long)

    private companion object {
        const val TAG = "TubeHome"

        const val LIKES_RELOAD_DEBOUNCE_MS = 1_000L
    }
}
