package tech.capullo.tubecapullo.station

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.capullo.audio.contracts.MediaRequest
import tech.capullo.audio.contracts.MediaSourceProvider
import tech.capullo.audio.contracts.PlaybackQueue
import tech.capullo.tubecapullo.data.history.LikedAlbums
import tech.capullo.tubecapullo.data.history.LikedTracks
import tech.capullo.tubecapullo.data.history.PlayHistory
import tech.capullo.tubecapullo.data.history.StationSnapshots
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.settings.LikeMode
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YouTubeUrls
import tech.capullo.tubecapullo.data.youtube.YtChapter
import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtVideo
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random

@Singleton
class StationEngine @Inject constructor(
    private val extractor: YouTubeExtractorClient,
    private val scope: CoroutineScope,
    private val playHistory: PlayHistory,
    private val likedTracks: LikedTracks,
    private val random: Random,
    private val settings: AppSettings,
    private val likedAlbums: LikedAlbums,
    private val snapshots: StationSnapshots,
) : MediaSourceProvider {
    private class Slot(
        val video: YtVideo,
        val why: Why,
        val pinned: Boolean = false,
        val blended: Boolean = false,
    )

    private class Feed(val seed: YtVideo, val lists: SongLists) {
        val mix = Why(Provenance.MIX, seed.videoId, seed.title).let { why ->
            lists.mix.map { Slot(it, why) }
        }
        val related = Why(Provenance.RELATED, seed.videoId, seed.title).let { why ->
            lists.related.map { Slot(it, why) }
        }

        fun merged(random: Random): List<Slot> = pairUp(mix, related, random)

        fun fields(added: List<Slot>): String {
            val fromMix = added.count { it.why.provenance == Provenance.MIX }
            val empty = when {
                mix.isEmpty() && related.isNotEmpty() -> " empty=mix"
                related.isEmpty() && mix.isNotEmpty() -> " empty=related"
                else -> ""
            }
            val dupes = if (lists.dupes > 0) " dupes=${lists.dupes}" else ""
            return "mix=+$fromMix related=+${added.size - fromMix}$empty$dupes"
        }
    }

    private val lock = Any()

    private var source: PlaySource? = null
    private val queue = mutableListOf<Slot>()
    private var current = -1

    private var stationId = 0L

    private var positionMs = 0L

    private var persistedAtMs = 0L

    private var resumeAt: Pair<Slot, Long>? = null

    private var chapterIndex = -1

    private var pendingRollChip: PlaySource? = null

    private val history = ArrayDeque<Slot>()

    private var steppingBackTo: Slot? = null

    private val dismissedIds = mutableSetOf<String>()

    private var chaining = false

    private var chainSeed: YtVideo? = null

    private var generation = 0

    private var startRequests = 0

    private var shuffle = false

    private val likesRound = ArrayDeque<YtVideo>()

    private var roundPlayed = 0

    private var fillGeneration = 0

    private val collection = mutableListOf<YtVideo>()

    private var collectionWhy: Why? = null

    private val round = mutableListOf<Slot>()

    private var roundPos = -1

    private var nextRound: List<Slot>? = null

    private var repeat = RepeatMode.OFF

    private val playedIds = mutableSetOf<String>()
    private val earlier = ArrayDeque<YtVideo>()
    private var playing: YtVideo? = null
    private var playCounted = false

    private val backStack = ArrayDeque<StationSnapshot>()

    private var nextStationId = System.currentTimeMillis()

    private var lastSavedAt = 0L

    private val writeLock = Mutex()
    private val written = HashMap<Long, Long>()

    private val likedVideos = mutableListOf<YtVideo>()
    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())

    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    private val _likedAlbumIds = MutableStateFlow<Set<String>>(emptySet())

    val likedAlbumIds: StateFlow<Set<String>> = _likedAlbumIds.asStateFlow()

    private val _state = MutableStateFlow(StationState())

    val state: StateFlow<StationState> = _state.asStateFlow()

    private val _likeEffects = MutableSharedFlow<LikeEffect>(extraBufferCapacity = 4)

    val likeEffects: SharedFlow<LikeEffect> = _likeEffects.asSharedFlow()

    private val _rollOvers = MutableSharedFlow<RollOver>(extraBufferCapacity = 4)

    val rollOvers: SharedFlow<RollOver> = _rollOvers.asSharedFlow()

    @Volatile var onStationStarted: (() -> Unit)? = null

    init {
        scope.launch {
            val persisted = likedTracks.all()
            val albums = likedAlbums.all()
            val stations = snapshots.all()
            synchronized(lock) {
                val known = likedVideos.mapTo(HashSet()) { it.videoId }
                likedVideos += persisted.filter { it.videoId !in known }
                refreshLikesLocked()
                _likedAlbumIds.value = _likedAlbumIds.value + albums.map { it.videoId }
                hydrateStationsLocked(stations)
                publishLocked()
            }
        }
    }

    fun start(source: PlaySource) {
        val request = synchronized(lock) {
            log("start ${describe(source)}")
            ++startRequests
        }
        when (source) {
            is PlaySource.SongRadio -> openRadio(request, source, source.seed, Provenance.SEED)
            is PlaySource.SearchRadio -> {
                val first = source.results.firstOrNull()
                if (first == null) {
                    log("start source=search result=idle reason=no-results")
                    return
                }
                openRadio(request, source, first, Provenance.SEARCH, source.results.drop(1))
            }
            is PlaySource.Playlist -> startPlaylist(request, source)
            is PlaySource.Channel -> startChannel(request, source)
            is PlaySource.Liked -> startLiked(request, source)
            PlaySource.LikedRadio -> startLikedRadio(request)
            is PlaySource.Album -> startAlbum(request, source)
        }
    }

    fun resume(id: Long) {
        val request = synchronized(lock) {
            log("resume station=$id")
            ++startRequests
        }
        scope.launch {
            val kept = synchronized(lock) { backStack.lastOrNull { it.id == id } }
            val saved = kept ?: snapshots.byId(id)
            synchronized(lock) {
                if (request != startRequests) {
                    log("resume station=$id result=dropped reason=superseded")
                    return@launch
                }
                if (saved == null || saved.id == stationId) {
                    val reason = if (saved == null) "not-found" else "playing"
                    log("resume station=$id result=ignored reason=$reason")
                    return@launch
                }
                saveStationLocked()
                backStack.removeAll { it.id == id }
                restoreLocked(saved)
                resumeAt = queue.first() to saved.positionMs
                log("snapshot restored via=continue ${describeSnapshot(saved)}")
                publishLocked()
            }
            onStationStarted?.invoke()
        }
    }

    fun startPositionFor(index: Int): Long? = synchronized(lock) {
        val (slot, at) = resumeAt ?: return null
        if (queue.getOrNull(index) !== slot) return null
        resumeAt = null
        at.takeIf { it > 0 }
    }

    fun toggleHeart(video: YtVideo) {
        val album = synchronized(lock) {
            (source as? PlaySource.Album)?.video?.videoId == video.videoId &&
                queue.getOrNull(current)?.why?.provenance == Provenance.ALBUM
        }
        if (album) toggleAlbumLike(video) else toggleLike(video)
    }

    fun toggleLike(video: YtVideo) {
        val id = video.videoId
        val becameLiked = synchronized(lock) {
            val liked = id !in _likedIds.value
            likedVideos.removeAll { it.videoId == id }
            if (liked) likedVideos.add(0, video)
            refreshLikesLocked()
            val verb = if (liked) "like" else "unlike"
            log("$verb id=$id title=${q(video.title)} uploader=${q(video.uploader)}")
            when (val src = source) {
                is PlaySource.Liked -> {
                    if (!liked) removeUnplayedLocked(id)
                    if (liked) log("like mode=none source=${kind(src)} +0")
                }
                null -> Unit
                else -> {
                    if (src == PlaySource.LikedRadio) {
                        if (!liked) {
                            likesRound.removeAll { it.videoId == id }
                        } else if (likesRound.none { it.videoId == id }) {
                            likesRound.addLast(video)
                        }
                    }
                    if (liked) likeLocked(src, video)
                }
            }
            publishLocked()
            liked
        }
        likedTracks.set(video, becameLiked)
    }

    fun onLikesImported(videos: List<YtVideo>) {
        synchronized(lock) {
            val known = likedVideos.mapTo(HashSet()) { it.videoId }
            val fresh = videos.filter { known.add(it.videoId) }
            likedVideos.addAll(0, fresh)
            refreshLikesLocked()
            if (source == PlaySource.LikedRadio) likesRound.addAll(fresh)
            log("likes-imported n=${fresh.size}")
            publishLocked()
        }
    }

    fun toggleAlbumLike(album: YtVideo) {
        val liked = synchronized(lock) {
            val liked = album.videoId !in _likedAlbumIds.value
            _likedAlbumIds.value = if (liked) {
                _likedAlbumIds.value + album.videoId
            } else {
                _likedAlbumIds.value - album.videoId
            }
            val verb = if (liked) "like" else "unlike"
            log("$verb kind=album id=${album.videoId} title=${q(album.title)} mode=save-only +0")
            liked
        }
        likedAlbums.set(album, liked)
    }

    fun onAlbumsImported(albums: List<YtVideo>) {
        synchronized(lock) {
            _likedAlbumIds.value = _likedAlbumIds.value + albums.map { it.videoId }
            log("albums-imported n=${albums.size}")
        }
    }

    fun playNext(video: YtVideo) {
        synchronized(lock) {
            val id = video.videoId
            if (queue.isEmpty()) {
                log("playNext id=$id result=ignored reason=no-station")
                return
            }
            removeUnplayedLocked(id, leaveList = false)
            dismissedIds -= id
            val at = maxOf(current, 0) + 1
            queue.add(at, Slot(video, Why(Provenance.USER, null, null), pinned = true))
            log("playNext id=$id title=${q(video.title)} at=$at")
            publishLocked()
        }
    }

    fun jumpTo(video: YtVideo, again: Boolean = false): Int? {
        synchronized(lock) {
            val id = video.videoId
            val from = playing?.videoId ?: "-"
            if (queue.isEmpty()) {
                log("jump id=$id from=$from result=ignored reason=no-station")
                return null
            }
            val at = maxOf(current, 0) + 1
            val inTail = queue.subList(at, queue.size).indexOfFirst { it.video.videoId == id }
            val queued = if (again || inTail < 0) -1 else at + inTail
            val row: String
            val slot = if (queued >= 0) {
                row = "queue"
                queue.removeAt(queued)
            } else {
                row = if (again) "earlier" else "other"
                Slot(video, Why(Provenance.USER, null, null), pinned = true)
            }
            dismissedIds -= id
            queue.add(at, slot)
            log("jump id=$id from=$from row=$row at=$at title=${q(video.title)}")
            publishLocked()
            return at
        }
    }

    fun previous(positionMs: Long): Int? {
        synchronized(lock) {
            fun restart(reason: String): Int? {
                log("previous action=restart pos=$positionMs reason=$reason")
                return null
            }
            val src = source ?: return restart("no-station")
            val now = queue.getOrNull(current) ?: return restart("no-track")
            if (positionMs > PREVIOUS_RESTART_MS) return restart("position")
            if (history.isEmpty() && backStack.isEmpty()) return restart("nothing-earlier")
            val back = history.removeLastOrNull()
            if (back == null) {
                val saved = backStack.removeLast()
                startRequests++
                restoreLocked(saved)
                log(
                    "previous action=restore-station from=${kind(src)} to=${kind(saved.source)} " +
                        "id=${saved.leftVideo.videoId} pos=$positionMs " +
                        "upcoming=${saved.upcoming.size} history=${history.size} " +
                        "stack=${backStack.size} title=${q(saved.leftVideo.title)}",
                )
                log("snapshot restored via=previous ${describeSnapshot(saved)}")
                publishLocked()
                return 0
            }
            val at = current + 1
            val again = Slot(back.video, back.why, pinned = true)
            queue.add(at, again)
            queue.add(at + 1, Slot(now.video, now.why, pinned = true))
            steppingBackTo = again
            log(
                "previous action=song id=${back.video.videoId} from=${now.video.videoId} " +
                    "pos=$positionMs at=$at history=${history.size} title=${q(back.video.title)}",
            )
            publishLocked()
            return at
        }
    }

    fun nextChapter(positionMs: Long): Long? {
        synchronized(lock) {
            val chapters = albumChaptersLocked() ?: return null
            val at = chapterAt(chapters, positionMs)
            val to = chapters.getOrNull(at + 1)
            if (to == null) {
                log("album chapter-next from=${at + 1}/${chapters.size} pos=$positionMs result=end")
                return null
            }
            log(
                "album chapter-next from=${at + 1} to=${at + 2}/${chapters.size} " +
                    "pos=$positionMs title=${q(to.title)}",
            )
            moveChapterLocked(at + 1)
            return to.startMs
        }
    }

    fun previousChapter(positionMs: Long): Long? {
        synchronized(lock) {
            val chapters = albumChaptersLocked() ?: return null
            val at = chapterAt(chapters, positionMs)
            val into = positionMs - chapters[at].startMs
            val to = when {
                into > PREVIOUS_RESTART_MS -> at
                at > 0 -> at - 1
                else -> {
                    log("album chapter-previous from=1/${chapters.size} pos=$positionMs result=song")
                    return null
                }
            }
            val action = if (to == at) "restart" else "back"
            log(
                "album chapter-previous action=$action from=${at + 1} to=${to + 1}/" +
                    "${chapters.size} pos=$positionMs title=${q(chapters[to].title)}",
            )
            moveChapterLocked(to)
            return chapters[to].startMs
        }
    }

    fun removeUpcoming(videoId: String) {
        synchronized(lock) {
            val removed = removeUnplayedLocked(videoId)
            dismissedIds += videoId
            likesRound.removeAll { it.videoId == videoId }
            log("remove id=$videoId queued=$removed")
            growLocked()
            publishLocked()
        }
    }

    fun setShuffle(on: Boolean) {
        synchronized(lock) {
            val src = source
            if (src == null) {
                log("shuffle on=$on result=ignored reason=no-station")
                return
            }
            shuffle = on
            if (src.isCollection) {
                val kept = round.take(maxOf(roundPos, 0) + 1)
                val rest = if (on) {
                    round.drop(kept.size).shuffled(random)
                } else {
                    val now = kept.lastOrNull()?.video?.videoId
                    val at = collection.indexOfFirst { it.videoId == now }
                    collection.drop(at + 1).map(::collectionSlotLocked)
                }
                round.clear()
                round += kept + rest
                nextRound = null
                log("shuffle on=$on rest=${rest.size}")
                relayCollectionLocked()
            } else if (src == PlaySource.LikedRadio) {
                log("shuffle on=$on rest=${relayLikedRadioLocked()}")
            } else {
                val moved = if (on) shuffleRadioTailLocked() else 0
                log("shuffle on=$on rest=$moved")
            }
            publishLocked()
        }
    }

    fun cycleRepeat() {
        synchronized(lock) {
            if (source?.isCollection != true) {
                log("repeat result=ignored reason=radio")
                return
            }
            repeat = when (repeat) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            log("repeat mode=$repeat")
            relayCollectionLocked()
            publishLocked()
        }
    }

    fun onProgress(videoId: String, positionMs: Long, durationMs: Long) {
        val counted = synchronized(lock) {
            val now = playing ?: return
            if (now.videoId != videoId) return
            this.positionMs = positionMs
            albumChaptersLocked()?.let { chapters ->
                val at = chapterAt(chapters, positionMs)
                if (at != chapterIndex) {
                    log(
                        "album chapter at=${at + 1}/${chapters.size} pos=$positionMs " +
                            "title=${q(chapters[at].title)}",
                    )
                    moveChapterLocked(at)
                }
            }
            if (abs(positionMs - persistedAtMs) >= SNAPSHOT_SAVE_MS) persistLocked("progress")
            if (playCounted) return
            val threshold = if (durationMs > 0) {
                minOf(PLAY_COUNT_MIN_MS, (durationMs * PLAY_COUNT_FRACTION).toLong())
            } else {
                PLAY_COUNT_MIN_MS
            }
            if (positionMs < threshold) return
            playCounted = true
            log("counted id=$videoId pos=$positionMs dur=$durationMs threshold=$threshold")
            now
        }
        scope.launch { playHistory.record(counted) }
    }

    fun dump(out: Appendable) {
        synchronized(lock) {
            val now = maxOf(current, 0)
            val nowSlot = queue.getOrNull(now)
            out.appendLine("source: ${source?.let { describe(it) } ?: "none"}")
            out.appendLine(
                "mix: playing-from=${mixSeedOf(nowSlot)} chain-seed=${chainSeed?.videoId ?: "-"} " +
                    "${q(chainSeed?.title)} chaining=$chaining",
            )
            out.appendLine(
                "settings: shuffle=$shuffle repeat=$repeat " +
                    "like-mode=${settings.likeMode.value} " +
                    "songs-per-like=${settings.songsPerLike.value}",
            )
            out.appendLine(
                "pairs: each fetch = Mix + all related (gated); 1 Mix + 1 related " +
                    "per pair, random order in a pair, each list in its own order; a short " +
                    "list's share filled by the other; one copy per title (most views)",
            )
            out.appendLine(
                "order: collection=${collection.size} round=${roundPos + 1}/${round.size} " +
                    "next-round=${nextRound?.size ?: 0}",
            )
            if (source == PlaySource.LikedRadio) {
                out.appendLine(
                    "liked radio: round=$roundPlayed/${roundPlayed + likesRound.size} " +
                        "fill-generation=$fillGeneration",
                )
            }
            out.appendLine(
                "current: ${nowSlot?.let { describeSlot(it) } ?: "none"} index=$current " +
                    "playing=${playing?.videoId ?: "none"} counted=$playCounted",
            )
            out.appendLine("earlier (last $DUMP_EARLIER of ${earlier.size}):")
            earlier.toList().takeLast(DUMP_EARLIER).forEach {
                out.appendLine("  ${describeVideo(it)}")
            }
            val tail = queue.drop(now + 1)
            out.appendLine("upcoming (${tail.size}):")
            tail.forEach { out.appendLine("  ${describeSlot(it)}") }
            out.appendLine("liked: count=${likedVideos.size}")
            out.appendLine("dismissed: ids=${dismissedIds.sorted()}")
            out.appendLine("session: played=${playedIds.size} generation=$generation")
            val stack = backStack.joinToString(",") { "${kind(it.source)}:${it.leftVideo.videoId}" }
            out.appendLine(
                "back: history=${history.size} " +
                    "last=${history.lastOrNull()?.video?.videoId ?: "-"} " +
                    "stepping-back-to=${steppingBackTo?.video?.videoId ?: "-"} " +
                    "stations=${backStack.size} stack=${stack.ifEmpty { "-" }}",
            )
            out.appendLine(
                "station: id=$stationId position=${positionMs}ms persisted-at=${persistedAtMs}ms " +
                    "resume-at=${resumeAt?.let { "${it.first.video.videoId}@${it.second}ms" } ?: "-"} " +
                    "roll-chip=${pendingRollChip?.let { kind(it) } ?: "-"}",
            )
            val album = source as? PlaySource.Album
            if (album != null) {
                val chapter = album.chapters.getOrNull(chapterIndex)
                out.appendLine(
                    "album: id=${album.video.videoId} chapters=${album.chapters.size} " +
                        "chapter=${chapterIndex + 1}/${album.chapters.size} ${q(chapter?.title)} " +
                        "liked=${album.video.videoId in _likedAlbumIds.value}",
                )
                album.chapters.forEachIndexed { i, it ->
                    out.appendLine("  ${i + 1} ${it.startMs / 1000}s ${q(it.title)}")
                }
            }
            out.appendLine("snapshots (stack, newest last; Room keeps $SNAPSHOTS_KEPT rows):")
            backStack.forEach { out.appendLine("  ${describeSnapshot(it)}") }
            out.appendLine("liked albums: count=${_likedAlbumIds.value.size}")
            out.appendLine(
                "constants: CHAIN_BELOW=$CHAIN_BELOW " +
                    "TRACK_SECONDS=$MIN_TRACK_SECONDS..$MAX_TRACK_SECONDS " +
                    "LIKED_RADIO_LOOKAHEAD=$LIKED_RADIO_LOOKAHEAD " +
                    "PLAY_COUNT_MIN_MS=$PLAY_COUNT_MIN_MS " +
                    "PLAY_COUNT_FRACTION=$PLAY_COUNT_FRACTION " +
                    "PREVIOUS_RESTART_MS=$PREVIOUS_RESTART_MS " +
                    "STATION_BACK_STACK=$STATION_BACK_STACK " +
                    "SNAPSHOT_SAVE_MS=$SNAPSHOT_SAVE_MS " +
                    "RELATED_MIN_VIEWS=$RELATED_MIN_VIEWS " +
                    "RELATED_MIN_SEED_FRACTION=$RELATED_MIN_SEED_FRACTION",
            )
        }
    }

    fun videoAt(index: Int): YtVideo? = synchronized(lock) { queue.getOrNull(index)?.video }

    override fun queue(): PlaybackQueue = object : PlaybackQueue {
        override val size: Int get() = synchronized(lock) { queue.size }
        override val currentIndex: Int get() = synchronized(lock) { current }
        override val isRotating: Boolean = false
        override fun idAt(index: Int): String? = synchronized(lock) {
            queue.getOrNull(index)?.video?.videoId
        }
    }

    override suspend fun mediaRequestFor(id: String): MediaRequest {
        val stream = extractor.resolveAudioStream(id)
        return MediaRequest(stream.url, stream.mimeType)
    }

    override fun onQueueAdvanced(index: Int) {
        val next = synchronized(lock) {
            val slot = queue.getOrNull(index) ?: return
            val newTrack = index != current
            if (newTrack) {
                playing?.let { pushEarlierLocked(it) }
                val stepBack = slot === steppingBackTo
                steppingBackTo = null
                if (!stepBack) queue.getOrNull(current)?.let(::pushHistoryLocked)
                playing = slot.video
                playCounted = false
                positionMs = resumeAt?.takeIf { it.first === slot }?.second ?: 0L
                persistedAtMs = positionMs
                log("advance index=$index ${describeSlot(slot)}")
            }
            current = index
            playedIds += slot.video.videoId
            val src = source
            if (src is PlaySource.Album) {
                if (slot.why.provenance == Provenance.ALBUM) {
                    chapterIndex = if (src.chapters.isEmpty()) -1 else chapterAt(src.chapters, positionMs)
                } else {
                    albumEndedLocked(src)
                }
            }
            if (source?.isCollection == true) {
                if (!slot.pinned) moveRoundToLocked(slot)
                relayCollectionLocked()
            }
            pendingRollChip?.let { from ->
                if (slot.why.provenance == Provenance.MIX || slot.why.provenance == Provenance.RELATED) {
                    pendingRollChip = null
                    log("roll-chip from=${kind(from)} at=${slot.video.videoId}")
                    _rollOvers.tryEmit(RollOver(from))
                }
            }
            growLocked()
            if (newTrack) persistLocked("track")
            publishLocked()
            queue.getOrNull(index + 1)?.video
        }
        next?.let { prefetch(it) }
    }

    fun persistNow() {
        synchronized(lock) { persistLocked("stop") }
    }

    private fun openRadio(
        request: Int,
        source: PlaySource,
        first: YtVideo,
        provenance: Provenance,
        extras: List<YtVideo> = emptyList(),
    ) {
        synchronized(lock) {
            if (request != startRequests) return
            resetLocked(source)
            queue += Slot(first, Why(provenance, null, null))
            if (extras.isNotEmpty()) {
                val fresh = freshLocked(extras, first)
                queue += fresh.map { Slot(it, Why(Provenance.SEARCH, null, null)) }
                log("seeded prov=SEARCH +${fresh.size} of ${extras.size}")
            }
            chainLocked(first, "start")
            publishLocked()
        }
        onStationStarted?.invoke()
    }

    private fun startPlaylist(request: Int, source: PlaySource.Playlist) {
        scope.launch {
            val list = runCatching { extractor.playlistInfo(source.url) }
                .onFailure { log("playlist url=${q(source.url)} error=${q(it.message)}") }
                .getOrNull()
            val videos = list?.videos.orEmpty()
            val title = list?.title
                ?.takeIf { it.isNotBlank() && it != YouTubeUrls.parsePlaylistId(source.url) }
                ?: source.title
            log("playlist title=${q(title)} url=${q(source.url)} tracks=${videos.size}")
            val named = source.copy(title = title)
            val why = Why(Provenance.PLAYLIST, null, title)
            openFetchedList(request, named, "playlist", videos, why, source.shuffle)
        }
    }

    private fun startChannel(request: Int, source: PlaySource.Channel) {
        scope.launch {
            val uploads = runCatching { extractor.channelUploads(source.url).videos }
                .onFailure { log("channel url=${q(source.url)} error=${q(it.message)}") }
                .getOrDefault(emptyList())
            val videos = uploads.ifEmpty {
                val results = runCatching { extractor.search(source.name) }
                    .onFailure { log("channel search=${q(source.name)} error=${q(it.message)}") }
                    .getOrDefault(emptyList())
                results.filter {
                    it.uploaderUrl == source.url ||
                        it.uploader.equals(source.name, ignoreCase = true)
                }
            }
            log(
                "channel url=${q(source.url)} name=${q(source.name)} uploads=${uploads.size} " +
                    "tracks=${videos.size}",
            )
            val why = Why(Provenance.CHANNEL, null, source.name)
            openFetchedList(request, source, "channel", videos, why, shuffled = false)
        }
    }

    private fun openFetchedList(
        request: Int,
        source: PlaySource,
        kind: String,
        videos: List<YtVideo>,
        why: Why,
        shuffled: Boolean,
    ) {
        synchronized(lock) {
            if (request != startRequests) {
                log("start source=$kind result=dropped reason=superseded")
                return
            }
            if (videos.isEmpty()) {
                log("start source=$kind result=idle reason=no-tracks")
                return
            }
            openCollectionLocked(source, videos, why, shuffled)
        }
        onStationStarted?.invoke()
    }

    private fun startLiked(request: Int, source: PlaySource.Liked) {
        synchronized(lock) {
            if (request != startRequests) return
            if (likedVideos.isEmpty()) {
                log("start source=liked result=idle reason=no-likes")
                return
            }
            val why = Why(Provenance.LIKED, null, null)
            openCollectionLocked(source, likedVideos.toList(), why, source.shuffle)
        }
        onStationStarted?.invoke()
    }

    private fun startLikedRadio(request: Int) {
        synchronized(lock) {
            if (request != startRequests) return
            if (likedVideos.isEmpty()) {
                log("start source=liked-radio result=idle reason=no-likes")
                return
            }
            resetLocked(PlaySource.LikedRadio)
            enterLikedRadioLocked()
            publishLocked()
        }
        onStationStarted?.invoke()
    }

    private fun startAlbum(request: Int, source: PlaySource.Album) {
        if (source.chapters.isNotEmpty()) {
            openAlbum(request, source)
            return
        }
        scope.launch {
            val fetched = runCatching { extractor.album(source.video.videoId) }
                .onFailure { log("album id=${source.video.videoId} error=${q(it.message)}") }
                .getOrNull()
            val album = fetched?.let { PlaySource.Album(it.video, it.chapters) } ?: source
            log(
                "album id=${album.video.videoId} chapters=${album.chapters.size} " +
                    "dur=${album.video.durationSeconds}s title=${q(album.video.title)}",
            )
            openAlbum(request, album)
        }
    }

    private fun openAlbum(request: Int, source: PlaySource.Album) {
        synchronized(lock) {
            if (request != startRequests) {
                log("start source=album result=dropped reason=superseded")
                return
            }
            resetLocked(source)
            queue += Slot(source.video, Why(Provenance.ALBUM, null, source.video.title))
            chainLocked(source.video, "album")
            publishLocked()
        }
        onStationStarted?.invoke()
    }

    private fun albumEndedLocked(album: PlaySource.Album) {
        log("rollover from=album to=song-radio at=${album.video.videoId}")
        source = PlaySource.SongRadio(album.video)
        chapterIndex = -1
        pendingRollChip = album
    }

    private fun albumChaptersLocked(): List<YtChapter>? {
        val album = source as? PlaySource.Album ?: return null
        val now = queue.getOrNull(current) ?: return null
        if (now.why.provenance != Provenance.ALBUM) return null
        return album.chapters.ifEmpty { null }
    }

    private fun moveChapterLocked(index: Int) {
        chapterIndex = index
        publishLocked()
    }

    private fun resetLocked(source: PlaySource) {
        saveStationLocked()
        clearStationLocked(source)
    }

    private fun clearStationLocked(source: PlaySource) {
        this.source = source
        stationId = nextStationId++
        generation++
        queue.clear()
        dismissedIds.clear()
        current = -1
        history.clear()
        steppingBackTo = null
        chaining = false
        chainSeed = null
        positionMs = 0
        persistedAtMs = 0
        resumeAt = null
        chapterIndex = -1
        pendingRollChip = null
        clearLikedRadioLocked()
        clearCollectionLocked()
    }

    private fun saveStationLocked() {
        val snapshot = snapshotLocked() ?: return
        backStack.addLast(snapshot)
        while (backStack.size > STATION_BACK_STACK) backStack.removeFirst()
        log("snapshot saved reason=left ${describeSnapshot(snapshot)}")
        write(snapshot)
    }

    private fun snapshotLocked(): StationSnapshot? {
        val src = source ?: return null
        val left = queue.getOrNull(current) ?: return null
        val slots = mutableListOf<SnapshotSlot>()
        val index = IdentityHashMap<Slot, Int>()
        fun ref(slot: Slot): Int = index.getOrPut(slot) {
            slots += SnapshotSlot(slot.video, slot.why, slot.pinned, slot.blended)
            slots.lastIndex
        }
        val leftAt = ref(left)
        val upcoming = queue.drop(current + 1).map(::ref)
        val past = history.map(::ref)
        val roundAt = round.map(::ref)
        val next = nextRound?.map(::ref)
        lastSavedAt = maxOf(System.currentTimeMillis(), lastSavedAt + 1)
        return StationSnapshot(
            id = stationId,
            savedAtEpochMs = lastSavedAt,
            source = src,
            slots = slots.toList(),
            left = leftAt,
            positionMs = positionMs,
            upcoming = upcoming,
            history = past,
            dismissedIds = dismissedIds.toSet(),
            chainSeed = chainSeed,
            shuffle = shuffle,
            repeat = repeat,
            likesRound = likesRound.toList(),
            roundPlayed = roundPlayed,
            collection = collection.toList(),
            collectionWhy = collectionWhy,
            round = roundAt,
            roundPos = roundPos,
            nextRound = next,
        )
    }

    private fun persistLocked(reason: String) {
        val snapshot = snapshotLocked() ?: return
        persistedAtMs = positionMs
        if (reason != "progress") log("snapshot saved reason=$reason ${describeSnapshot(snapshot)}")
        write(snapshot)
    }

    private fun write(snapshot: StationSnapshot) {
        scope.launch {
            writeLock.withLock {
                if ((written[snapshot.id] ?: Long.MIN_VALUE) > snapshot.savedAtEpochMs) return@withLock
                written[snapshot.id] = snapshot.savedAtEpochMs
                snapshots.save(snapshot, SNAPSHOTS_KEPT)
            }
        }
    }

    private fun hydrateStationsLocked(rows: List<StationSnapshot>) {
        val known = backStack.mapTo(HashSet()) { it.id }.apply { add(stationId) }
        val older = rows.filter { it.id !in known }.take(STATION_BACK_STACK).reversed()
        backStack.addAll(0, older)
        while (backStack.size > STATION_BACK_STACK) backStack.removeFirst()
        rows.maxOfOrNull { it.id }?.let { nextStationId = maxOf(nextStationId, it + 1) }
        rows.maxOfOrNull { it.savedAtEpochMs }?.let { lastSavedAt = maxOf(lastSavedAt, it) }
        log("snapshot hydrated rows=${rows.size} stack=${backStack.size}")
    }

    private fun restoreLocked(saved: StationSnapshot) {
        clearStationLocked(saved.source)
        stationId = saved.id
        val slots = saved.slots.map { Slot(it.video, it.why, it.pinned, it.blended) }
        queue += slots[saved.left]
        queue += saved.upcoming.map(slots::get)
        history += saved.history.map(slots::get)
        dismissedIds += saved.dismissedIds
        chainSeed = saved.chainSeed
        likesRound += saved.likesRound
        roundPlayed = saved.roundPlayed
        collection += saved.collection
        collectionWhy = saved.collectionWhy
        round += saved.round.map(slots::get)
        roundPos = saved.roundPos
        nextRound = saved.nextRound?.map(slots::get)
        shuffle = saved.shuffle
        repeat = saved.repeat
    }

    private fun growLocked() {
        when (val src = source) {
            is PlaySource.SongRadio, is PlaySource.SearchRadio -> {
                val last = playing ?: return
                if (!chaining && upcomingLocked() < CHAIN_BELOW) chainLocked(last, "low")
            }
            is PlaySource.Album ->
                if (!chaining && upcomingLocked() < CHAIN_BELOW) chainLocked(src.video, "album")
            PlaySource.LikedRadio -> fillLikedRadioLocked()
            is PlaySource.Liked, is PlaySource.Playlist, is PlaySource.Channel, null -> Unit
        }
    }

    private fun chainLocked(seed: YtVideo, reason: String) {
        chaining = true
        chainSeed = seed
        fetchSong(seed, "chain", skipQueued = true) { feed ->
            chaining = false
            val merged = feed.merged(random)
            val added = if (shuffle) merged.shuffled(random) else merged
            queue += added
            log(
                "chain seed=${seed.videoId} title=${q(seed.title)} reason=$reason " +
                    "${feed.fields(added)} shuffled=$shuffle upcoming=${upcomingLocked()}",
            )
        }
    }

    private fun likeLocked(src: PlaySource, liked: YtVideo) {
        val at = "source=${kind(src)} seed=${liked.videoId} title=${q(liked.title)}"
        when (settings.likeMode.value) {
            LikeMode.SAVE_ONLY -> log("like mode=save-only $at +0")
            LikeMode.BLEND -> {
                val count = settings.songsPerLike.value
                if (count == 0) {
                    log("like mode=blend $at +0 reason=songs-per-like-0")
                    return
                }
                val moveUp = src is PlaySource.SongRadio || src is PlaySource.SearchRadio
                fetchSong(liked, "like", skipQueued = !moveUp) { feed ->
                    val picks = feed.merged(random).take(count)
                    blendLocked(picks)
                    log("like mode=blend $at ${feed.fields(picks)}")
                    _likeEffects.tryEmit(LikeEffect.Blended(liked, picks.size))
                }
            }
            LikeMode.SWITCH_NOW -> fetchSong(liked, "like", skipQueued = false) { feed ->
                val picks = feed.merged(random)
                if (picks.isNotEmpty()) switchLocked(liked, picks)
                val result = if (picks.isEmpty()) " result=kept" else ""
                log("like mode=switch-now $at ${feed.fields(picks)}$result")
                _likeEffects.tryEmit(LikeEffect.Switched(liked, picks.size))
            }
        }
    }

    private fun blendLocked(picks: List<Slot>) {
        val ids = picks.mapTo(HashSet()) { it.video.videoId }
        val tailStart = maxOf(current, 0) + 1
        for (i in queue.lastIndex downTo tailStart) {
            if (!queue[i].pinned && queue[i].video.videoId in ids) queue.removeAt(i)
        }
        var at = tailStart
        while (at < queue.size && queue[at].pinned) at++
        val earlierBlend = mutableListOf<Slot>()
        while (at < queue.size && queue[at].blended) earlierBlend += queue.removeAt(at)
        val fresh = picks.map { Slot(it.video, it.why, blended = true) }
        val merged = buildList {
            for (i in 0 until maxOf(fresh.size, earlierBlend.size)) {
                fresh.getOrNull(i)?.let(::add)
                earlierBlend.getOrNull(i)?.let(::add)
            }
        }
        queue.addAll(at, merged)
    }

    private fun switchLocked(liked: YtVideo, picks: List<Slot>) {
        val tail = queue.subList(maxOf(current, 0) + 1, queue.size)
        val pins = tail.filter { it.pinned }
        tail.clear()
        queue += pins
        val from = source
        if (from != null && from !is PlaySource.SongRadio && from !is PlaySource.SearchRadio) {
            clearCollectionLocked()
            clearLikedRadioLocked()
            chaining = false
            shuffle = false
            source = PlaySource.SongRadio(liked)
            log("switch from=${kind(from)} to=song-radio seed=${liked.videoId}")
        }
        queue += if (shuffle) picks.shuffled(random) else picks
        chainSeed = liked
    }

    private fun shuffleRadioTailLocked(): Int {
        val places = (maxOf(current, 0) + 1 until queue.size).filter { i ->
            val slot = queue[i]
            !slot.pinned && !slot.blended &&
                (slot.why.provenance == Provenance.MIX || slot.why.provenance == Provenance.RELATED)
        }
        val shuffled = places.map { queue[it] }.shuffled(random)
        places.forEachIndexed { n, i -> queue[i] = shuffled[n] }
        return places.size
    }

    private fun fetchSong(
        seed: YtVideo,
        what: String,
        skipQueued: Boolean,
        land: (Feed) -> Unit,
    ) {
        val gen = generation
        fun stale(): Boolean {
            if (gen == generation) return false
            log("$what seed=${seed.videoId} result=dropped reason=stale-station")
            return true
        }
        scope.launch {
            val (mix, related) = coroutineScope {
                val mix = async {
                    runCatching { extractor.mix(seed.videoId) }
                        .onFailure { log("$what seed=${seed.videoId} mix-error=${q(it.message)}") }
                        .getOrDefault(emptyList())
                }
                val related = async {
                    runCatching { extractor.relatedStreams(seed.videoId) }
                        .onFailure {
                            log("$what seed=${seed.videoId} related-error=${q(it.message)}")
                        }
                        .getOrNull()
                }
                mix.await() to related.await()
            }
            synchronized(lock) {
                if (stale()) return@launch
                val lists = songLists(
                    seed,
                    mix,
                    related ?: YtRelated(seedViews = -1, videos = emptyList()),
                    excludedIdsLocked(skipQueued),
                )
                if (related != null) {
                    log(
                        "related gate seed=${seed.videoId} kept=${lists.gateKept} " +
                            "dropped=${lists.gateDropped}",
                    )
                }
                land(Feed(seed, lists))
                publishLocked()
            }
        }
    }

    private fun excludedIdsLocked(skipQueued: Boolean): MutableSet<String> {
        val ids = HashSet<String>(playedIds)
        ids += dismissedIds
        if (skipQueued) queue.mapTo(ids) { it.video.videoId }
        if (source == PlaySource.LikedRadio) ids += _likedIds.value
        return ids
    }

    private fun freshLocked(videos: List<YtVideo>, first: YtVideo): List<YtVideo> {
        val known = excludedIdsLocked(skipQueued = true).apply { add(first.videoId) }
        return videos.filter { it.inTrackWindow() && known.add(it.videoId) }
    }

    private fun clearLikedRadioLocked() {
        likesRound.clear()
        roundPlayed = 0
        fillGeneration++
    }

    private fun enterLikedRadioLocked() {
        source = PlaySource.LikedRadio
        clearLikedRadioLocked()
        shuffle = true
        fillLikedRadioLocked()
    }

    private fun fillLikedRadioLocked() {
        while (!chaining && upcomingLocked() < LIKED_RADIO_LOOKAHEAD) {
            val like = nextLikeLocked() ?: return
            queue += like
            val count = settings.songsPerLike.value
            if (count == 0) {
                logLikedRadio(like.video, "+0")
                continue
            }
            chaining = true
            chainSeed = like.video
            val layout = fillGeneration
            fetchSong(like.video, "liked-radio", skipQueued = true) { feed ->
                if (layout != fillGeneration) {
                    log("liked-radio like=${like.video.videoId} result=dropped reason=relaid")
                    return@fetchSong
                }
                chaining = false
                val picks = feed.merged(random).take(count)
                queue += picks
                logLikedRadio(like.video, feed.fields(picks))
                fillLikedRadioLocked()
            }
        }
    }

    private fun nextLikeLocked(): Slot? {
        if (likesRound.isEmpty()) {
            val likes = likedVideos.filter { it.videoId !in dismissedIds }
            if (likes.isEmpty()) return null
            likesRound += if (shuffle) likes.shuffled(random) else likes
            roundPlayed = 0
            val last = queue.lastOrNull { it.isRoundLike }?.video?.videoId
            if (likesRound.size > 1 && likesRound.first().videoId == last) {
                likesRound.addLast(likesRound.removeFirst())
            }
        }
        val like = likesRound.removeFirstOrNull() ?: return null
        roundPlayed++
        return Slot(like, Why(Provenance.LIKED, null, null))
    }

    private fun relayLikedRadioLocked(): Int {
        val tailStart = maxOf(current, 0) + 1
        val firstLike = (tailStart until queue.size).firstOrNull { queue[it].isRoundLike }
        val takenBack = mutableListOf<YtVideo>()
        if (firstLike != null) {
            val dropped = queue.subList(firstLike, queue.size)
            val kept = dropped.filter { it.pinned || it.blended }
            takenBack += dropped.filter { it.isRoundLike }.map { it.video }
            dropped.clear()
            queue += kept
            roundPlayed = maxOf(roundPlayed - takenBack.size, 0)
            fillGeneration++
            chaining = false
        }
        val waiting = (takenBack + likesRound).distinctBy { it.videoId }
        val order = if (shuffle) {
            waiting.shuffled(random)
        } else {
            val rank = likedVideos.withIndex().associate { (i, video) -> video.videoId to i }
            waiting.sortedBy { rank[it.videoId] ?: Int.MAX_VALUE }
        }
        likesRound.clear()
        likesRound += order
        fillLikedRadioLocked()
        return order.size
    }

    private val Slot.isRoundLike: Boolean
        get() = why.provenance == Provenance.LIKED && !pinned && !blended

    private fun logLikedRadio(like: YtVideo, added: String) {
        log(
            "liked-radio like=${like.videoId} title=${q(like.title)} " +
                "round=$roundPlayed/${roundPlayed + likesRound.size} $added",
        )
    }

    private fun openCollectionLocked(
        source: PlaySource,
        tracks: List<YtVideo>,
        why: Why,
        shuffled: Boolean,
    ) {
        resetLocked(source)
        collection += tracks
        collectionWhy = why
        shuffle = shuffled
        round += newRoundLocked()
        queue += round
        log("collection tracks=${tracks.size} shuffle=$shuffle")
        publishLocked()
    }

    private fun clearCollectionLocked() {
        collection.clear()
        collectionWhy = null
        round.clear()
        roundPos = -1
        nextRound = null
        shuffle = false
        repeat = RepeatMode.OFF
    }

    private fun collectionSlotLocked(video: YtVideo): Slot {
        val why = checkNotNull(collectionWhy) { "no collection playing" }
        return Slot(video, why)
    }

    private fun newRoundLocked(): List<Slot> {
        val order = if (shuffle) collection.shuffled(random) else collection
        return order.map(::collectionSlotLocked)
    }

    private fun moveRoundToLocked(slot: Slot) {
        val at = round.indexOf(slot)
        if (at >= 0) {
            roundPos = at
            return
        }
        val next = nextRound ?: return
        val nextAt = next.indexOf(slot)
        if (nextAt < 0) return
        round.clear()
        round += next
        nextRound = null
        roundPos = nextAt
        log("wrap repeat=$repeat shuffle=$shuffle tracks=${round.size}")
    }

    private fun relayCollectionLocked() {
        if (source?.isCollection != true || queue.isEmpty()) return
        val tail = queue.subList(maxOf(current, 0) + 1, queue.size)
        val extras = tail.filter { it.pinned || it.blended }
        tail.clear()
        queue += extras
        val rest = round.drop(maxOf(roundPos, 0) + 1)
        queue += rest
        when {
            rest.isNotEmpty() || roundPos < 0 -> nextRound = null
            repeat == RepeatMode.OFF -> {
                nextRound = null
                rolloverLocked()
            }
            else -> queue += nextRound ?: newRoundLocked().also { nextRound = it }
        }
    }

    private fun rolloverLocked() {
        val from = source ?: return
        val last = round.getOrNull(roundPos)?.video ?: return
        clearCollectionLocked()
        if (from is PlaySource.Liked) {
            log("rollover from=liked to=liked-radio at=${last.videoId}")
            enterLikedRadioLocked()
            return
        }
        log("rollover from=${kind(from)} to=song-radio at=${last.videoId}")
        source = PlaySource.SongRadio(last)
        pendingRollChip = from
        chainLocked(last, "rollover")
    }

    private fun removeUnplayedLocked(videoId: String, leaveList: Boolean = true): Int {
        val tailStart = maxOf(current, 0) + 1
        var removed = 0
        for (i in queue.lastIndex downTo tailStart) {
            if (queue[i].video.videoId == videoId) {
                queue.removeAt(i)
                removed++
            }
        }
        if (source?.isCollection == true) {
            if (leaveList) collection.removeAll { it.videoId == videoId }
            round.subList(minOf(maxOf(roundPos, 0) + 1, round.size), round.size)
                .removeAll { it.video.videoId == videoId }
            nextRound = nextRound?.filterNot { it.video.videoId == videoId }
            relayCollectionLocked()
        }
        return removed
    }

    private fun upcomingLocked(): Int = queue.size - 1 - current

    private fun prefetch(next: YtVideo) {
        scope.launch { runCatching { extractor.resolveAudioStream(next.videoId) } }
    }

    private fun refreshLikesLocked() {
        _likedIds.value = likedVideos.mapTo(LinkedHashSet()) { it.videoId }
    }

    private fun pushEarlierLocked(video: YtVideo) {
        earlier.addLast(video)
        while (earlier.size > EARLIER_MAX) earlier.removeFirst()
    }

    private fun pushHistoryLocked(slot: Slot) {
        history.addLast(slot)
        while (history.size > EARLIER_MAX) history.removeFirst()
    }

    private fun publishLocked() {
        val now = maxOf(current, 0)
        val album = source as? PlaySource.Album
        _state.value = StationState(
            source = source,
            current = queue.getOrNull(now)?.video,
            earlier = earlier.toList(),
            queued = queue.drop(now + 1).map { UpNextItem(it.video, it.why, it.pinned) },
            isCollection = source?.isCollection == true,
            shuffle = shuffle,
            repeat = repeat,
            stationId = stationId,
            album = album?.takeIf { albumChaptersLocked() != null }
                ?.let { AlbumProgress(it.chapters, maxOf(chapterIndex, 0)) },
        )
    }

    private fun log(event: String) {
        Log.i(TAG, event)
    }

    private fun describe(source: PlaySource): String = when (source) {
        is PlaySource.SongRadio ->
            "source=song seed=${source.seed.videoId} title=${q(source.seed.title)}"
        is PlaySource.SearchRadio ->
            "source=search query=${q(source.query)} results=${source.results.size}"
        is PlaySource.Playlist -> "source=playlist title=${q(source.title)} " +
            "url=${q(source.url)} shuffle=${source.shuffle}"
        is PlaySource.Channel -> "source=channel name=${q(source.name)} url=${q(source.url)}"
        is PlaySource.Liked -> "source=liked shuffle=${source.shuffle} likes=${likedVideos.size}"
        PlaySource.LikedRadio ->
            "source=liked-radio likes=${likedVideos.size} " +
                "songs-per-like=${settings.songsPerLike.value}"
        is PlaySource.Album ->
            "source=album id=${source.video.videoId} " +
                "chapters=${source.chapters.size} title=${q(source.video.title)}"
    }

    private fun kind(source: PlaySource): String = when (source) {
        is PlaySource.SongRadio -> "song"
        is PlaySource.SearchRadio -> "search"
        is PlaySource.Playlist -> "playlist"
        is PlaySource.Channel -> "channel"
        is PlaySource.Liked -> "liked"
        PlaySource.LikedRadio -> "liked-radio"
        is PlaySource.Album -> "album"
    }

    private fun describeSnapshot(saved: StationSnapshot): String = "station=${saved.id} source=${kind(saved.source)} id=${saved.leftVideo.videoId} " +
        "pos=${saved.positionMs} upcoming=${saved.upcoming.size} history=${saved.history.size} " +
        "round=${saved.roundPos + 1}/${saved.round.size} shuffle=${saved.shuffle} " +
        "repeat=${saved.repeat} title=${q(saved.leftVideo.title)}"

    private fun mixSeedOf(slot: Slot?): String = slot?.why
        ?.takeIf { it.provenance == Provenance.MIX || it.provenance == Provenance.RELATED }
        ?.fromVideoId ?: "-"

    private fun describeVideo(video: YtVideo): String = "${video.videoId} ${q(video.title)} uploader=${q(video.uploader)} dur=${video.durationSeconds}s"

    private fun describeSlot(slot: Slot): String = "id=${describeVideo(slot.video)} prov=${slot.why.provenance} " +
        "from=${slot.why.fromVideoId ?: "-"}" +
        (if (slot.pinned) " pinned" else "") + (if (slot.blended) " blended" else "")

    private fun q(text: String?): String = if (text == null) "-" else "\"$text\""

    companion object {
        private const val TAG = "TubeStation"

        internal const val CHAIN_BELOW = 5

        internal const val LIKED_RADIO_LOOKAHEAD = 6

        internal const val MIN_TRACK_SECONDS = 60L
        internal const val MAX_TRACK_SECONDS = 15 * 60L

        internal const val PLAY_COUNT_MIN_MS = 30_000L

        internal const val PLAY_COUNT_FRACTION = 0.5

        private const val EARLIER_MAX = 50

        private const val DUMP_EARLIER = 10

        internal const val PREVIOUS_RESTART_MS = 3_000L

        internal const val STATION_BACK_STACK = 5

        internal const val SNAPSHOT_SAVE_MS = 10_000L

        private const val SNAPSHOTS_KEPT = AppSettings.MAX_CONTINUE_CARDS + 1
    }
}

internal fun chapterAt(chapters: List<YtChapter>, positionMs: Long): Int = chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
