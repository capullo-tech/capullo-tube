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

/** Home's parts as last fetched or observed; [sections] and [error] derive from them. */
data class HomeUiState(
    /** Live from the stored stations (see [continueCards]), not part of a [HomeViewModel.load]. */
    val continueCards: List<ContinueCard> = emptyList(),
    /** Live from likes and plays (see [listenAgainCards]); its random pick is per load. */
    val listenAgain: HomeSection.ListenAgain? = null,
    val moreLike: List<HomeSection.BecauseLiked> = emptyList(),
    val fullAlbums: List<YtVideo> = emptyList(),
    /** One fetch per chosen chart, in the chosen order. */
    val trending: List<TrendingFetch> = emptyList(),
    val isLoading: Boolean = true,
) {
    /** In display order (see [homeSections]). Before the first load completes, at most the
     *  local rows. */
    val sections: List<HomeSection> = homeSections(continueCards, listenAgain, moreLike, fullAlbums, trending)

    /** Set only when every Trending chart failed (see [trendingError]). */
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
    /** Which list leads each pair of a "More like" row ([becauseLikedCards]), and each load's
     *  Listen again pick. */
    private val random: Random,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()

    /** The player's state, for the "playing" marker on the matching card. */
    val playerState = player.state

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast = _toast.asSharedFlow()

    /** Listen again's per-load draw: the ranking seed and the clock its age rule reads. */
    private val listenAgainDraw = MutableStateFlow(ListenAgainDraw(random.nextInt(), System.currentTimeMillis()))

    private var loadJob: Job? = null
    private var moreLikeJob: Job? = null
    private var fullAlbumsJob: Job? = null
    private var trendingJob: Job? = null

    init {
        // Service-side events (skipped tracks, station ran dry) surface as toasts, same as search.
        viewModelScope.launch { player.events.collect { _toast.tryEmit(it) } }
        load()
        // Settings opens on top of the Home tab, so this ViewModel outlives a change there and
        // must show it without a manual refresh. Each setting reloads only the rows it shapes;
        // drop(1) skips the current value, which the load() above already used.
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
        // A like or unlike changes the "More like" seeds, the Full albums seeds and both rows'
        // filters; an album like changes the Full albums filter. Trending stays: a heart tap
        // must not refetch every chart. The first emission is what the load() above already
        // read; the debounce turns a burst of hearts into one reload.
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
        // Continue and Listen again are local and cheap, so they follow their sources live: a
        // station left or saved, a counted play, a like, a new playing track. Only the ids
        // matter, so play/pause and buffering changes are dropped.
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

    /**
     * Fresh fetch per launch - the kiosk is a chart snapshot (~0.9 s per fetch, probe
     * 2026-11-10, docs/probes/trending-music-results.md), so there is nothing worth caching.
     * [load] is also the retry path after an error and the pull-to-refresh action; a newer call
     * cancels the one in flight, and any partial reload with it. Every Trending chart, every
     * "More like" row and the Full albums row fetch in parallel, and each source fails alone: a
     * failed fetch or one failed chart hides only its own section. The two rows share one
     * related fetch per liked song. Listen again draws a new random pick; it and Continue are
     * live (see init).
     */
    fun load() {
        loadJob?.cancel()
        listOf(moreLikeJob, fullAlbumsJob, trendingJob).forEach { it?.cancel() }
        listenAgainDraw.value = ListenAgainDraw(random.nextInt(), System.currentTimeMillis())
        // Failed charts go now, so the banner leaves while its retry runs.
        _uiState.update { state ->
            state.copy(isLoading = true, trending = state.trending.filter { it.error == null })
        }
        loadJob = viewModelScope.launch {
            val related = RelatedFetches(this)
            val trending = async { trendingFetches() }
            // Each row already collapses its own failures to empty; the guard only keeps a
            // surprise from cancelling the fetches beside it.
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

    /** Both rows that start from the likes, sharing one related fetch per liked song. The
     *  shared fetches belong to the ViewModel: a reload that a newer one cancels leaves at most
     *  one fetch to finish unread. */
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

    /**
     * Fetches one part of Home again and leaves the others as they are; a newer reload of the
     * same part replaces [previous]. A full [load] in flight fetches that part too, but maybe
     * from values read before the change, so the reload waits for it and then fetches again.
     */
    private fun reloadPart(previous: Job?, fetchAndApply: suspend () -> Unit): Job {
        previous?.cancel()
        return viewModelScope.launch {
            loadJob?.join()
            fetchAndApply()
        }
    }

    /**
     * One fetch per chosen chart ([AppSettings.chartRegions]), in parallel, in the chosen order.
     * None chosen fetches [defaultChartRegion] for the device locale, forced like a chosen one,
     * so a device in a country YouTube Charts does not serve gets a neighbour's chart instead of
     * the kiosk's error. A failed chart keeps its error, so [trendingError] can tell one failure
     * from all of them.
     */
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
                        // A newer load cancelled this one: not an error (same rule as Search).
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

    /**
     * One row per like for the newest [AppSettings.moreLikeRows] likes, each like's Mix and
     * related videos fetched in parallel; 0 fetches nothing. A row whose two fetches both fail,
     * or a row left empty once liked tracks are removed, is dropped alone; one failed list
     * leaves the row to the other. Liked songs only: albums are in their own table.
     */
    private suspend fun moreLikeRows(related: RelatedFetches): List<HomeSection.BecauseLiked> = coroutineScope {
        val rows = appSettings.moreLikeRows.value
        if (rows == 0) return@coroutineScope emptyList()
        val likes = likedTracks.all() // newest like first
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

    /**
     * The Full albums row (FullAlbums.kt): the related lists of the newest [FULL_ALBUM_SEEDS]
     * liked songs, the long items there that pass the gate and are not liked, kept by an album
     * title or by chapters ([FULL_ALBUM_CHAPTER_CHECKS] fetches at most); under
     * [FULL_ALBUMS_MIN_CARDS] cards, a "<artist> full album" search of the most liked artists
     * tops the row up. One card per album across both ([fullAlbumCards]), at most
     * [FULL_ALBUM_CARDS]. Off in Settings, or with no likes, it fetches nothing.
     */
    private suspend fun fullAlbumsRow(related: RelatedFetches): List<YtVideo> = coroutineScope {
        if (!appSettings.fullAlbums.value) return@coroutineScope emptyList()
        val likes = likedTracks.all()
        if (likes.isEmpty()) return@coroutineScope emptyList()
        val excluded = likes.mapTo(HashSet()) { it.videoId }
        likedAlbums.all().mapTo(excluded) { it.videoId }
        // Every liked artist, so a fan upload's "Artist - Album" matches the artist's own upload.
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
            // The same dedupe over both lists: a searched upload of a related album is a dupe.
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

    /** The ids of [videos] that have chapters: one album fetch each, in parallel; a failed fetch
     *  counts as no chapters. */
    private suspend fun chapteredIds(videos: List<YtVideo>): Set<String> = coroutineScope {
        videos.map { video ->
            async {
                fetchOrNull("fullAlbums", "chapters", video) { extractor.album(video.videoId) }
                    ?.takeIf { it.chapters.isNotEmpty() }?.video?.videoId
            }
        }.awaitAll().filterNotNullTo(HashSet())
    }

    /** [fetch] for a row, or null when it failed (logged under [row]). A cancellation is not a
     *  failure: a newer load replaced this one. */
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

    /** One related fetch per liked song per load or like reload, shared by the "More like" and
     *  Full albums rows; the fetches belong to [scope] and end with it. */
    private inner class RelatedFetches(private val scope: CoroutineScope) {
        private val fetches = HashMap<String, Deferred<YtRelated?>>()

        suspend fun of(seed: YtVideo, row: String): YtRelated? = synchronized(fetches) {
            fetches.getOrPut(seed.videoId) {
                scope.async { fetchOrNull(row, "related", seed) { extractor.relatedStreams(seed.videoId) } }
            }
        }.await()
    }

    /** Start a station from [video] - the card-tap action. Mirrors SearchViewModel.play; keep the
     *  two in step. The coordinator starts the service. */
    fun play(video: YtVideo) {
        player.start(PlaySource.SongRadio(video))
    }

    /** An album card's tap: the video plays as an album (its chapters are fetched first). */
    fun playAlbum(video: YtVideo) {
        player.start(PlaySource.Album(video))
    }

    /** A Continue card's tap: its station comes back where it was left. */
    fun resume(card: ContinueCard) {
        player.resume(card.stationId)
    }

    /** Pin [video] after the playing track - Play next in a card's menu, shown only while a
     *  station plays. The engine logs the pin (`playNext id=... at=...`). */
    fun playNext(video: YtVideo) = stationEngine.playNext(video)

    private suspend fun <T> orEmpty(block: suspend () -> List<T>): List<T> = runCatching {
        block()
    }.getOrElse {
        if (it is CancellationException) throw it
        Log.w(TAG, "home section read failed", it)
        emptyList()
    }

    // Same translation as SearchViewModel.readable - YouTubeError already carries
    // listener-readable text (BotCheck included); anything else is a surprise, so say so.
    private fun Throwable.readable(): String = when (this) {
        is YouTubeError -> message ?: "YouTube request failed"
        else -> message ?: "Something went wrong"
    }

    /** One load's Listen again draw ([listenAgainCards]). */
    private data class ListenAgainDraw(val seed: Int, val nowEpochMs: Long)

    private companion object {
        const val TAG = "TubeHome"

        /**
         * Quiet time after the last like or unlike before the "More like" and Full albums rows
         * reload, in ms. GUESS, unmeasured: long enough that hearting a few songs in a row costs
         * one reload (each row is a full StreamInfo fetch and a Mix fetch), short enough that the
         * rows have changed by the time the listener comes back to Home. Lower refetches per
         * heart tap; higher leaves stale rows on screen after a quick like and return.
         */
        const val LIKES_RELOAD_DEBOUNCE_MS = 1_000L
    }
}
