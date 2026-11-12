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

/**
 * The station: a [PlaySource] becomes a queue, and a radio's queue grows by chaining YouTube's
 * own Mixes (PLAN "Radio", approved 2026-11-11). YouTube's ordering is kept; the engine only adds
 * what a Mix cannot do.
 *
 * Implements the contracts SPI ([MediaSourceProvider]) so the station is already the shape a
 * `capullo-source-youtube` library would export (HANDOFF-bootstrap §1). The queue is FINITE AND
 * GROWING - `isRotating = false`, because rotating means "live stream, never auto-advance" in the
 * engine's semantics (§3.4), and growth happens in [onQueueAdvanced] before the player needs the
 * next id.
 *
 * Every fetch from a song takes both of its lists (owner decision 2026-11-12, "Pairs",
 * station/Pairs.kt): its Mix in YouTube's order (`mix(id)`, ~50 unique tracks per the RD probe,
 * docs/probes/rd-mix-results.md; the seed arrives as item #1 and is dropped) and all of its
 * related videos in YouTube's order (about 20), through the related gate. Both leave out
 * songs played this session ([playedIds] survives [start]), songs already upcoming, songs the
 * listener removed, songs outside [MIN_TRACK_SECONDS]..[MAX_TRACK_SECONDS] and all but the most
 * viewed of tracks with one title. They are queued in pairs, one Mix and one related track in a
 * random order ([pairUp]); when one list runs out, the rest of the other follows.
 *
 * Radios ([PlaySource.SongRadio], [PlaySource.SearchRadio]). The upcoming list is the real queue,
 * all of it: the seed (or the first search result, then the other results), then the seed's
 * pairs. When fewer than [CHAIN_BELOW] tracks are upcoming, the pairs of the last song played
 * are appended (a chain): all of its Mix and its related list. A skip is only an advance: it
 * carries no signal and changes no state. [setShuffle] shuffles the rest of the chained tracks
 * in place; chains are shuffled on append while it is on. Radios have no repeat.
 *
 * A like follows `AppSettings.likeMode`, read at the like ([toggleLike]), in every source but
 * Liked (where every song is already liked and the heart only unlikes). BLEND puts the first N
 * (`AppSettings.songsPerLike`, "Songs added per like": N/2 pairs) unplayed tracks of the liked
 * song's pairs right after the current one and its pins; the radio's queue or the list resumes
 * after them. In a list they are extras, outside the round: the list's place and its repeat
 * round do not move. N = 0 only saves. SAVE_ONLY only saves; SWITCH_NOW replaces the upcoming
 * list with the liked song's pairs, and a list or Liked radio becomes a song radio from the liked
 * song. Each fetched outcome is announced on [likeEffects] for the Player's chip.
 *
 * Liked radio ([PlaySource.LikedRadio]) is likes in rounds - shuffled per round while shuffle is
 * on (the default there), newest first while it is off; no like repeats before all have played -
 * and after each like the first N tracks of that like's pairs (unplayed, in the window, not
 * liked). N = 0 plays likes only. Whole units (a like and its N tracks) are queued while fewer
 * than [LIKED_RADIO_LOOKAHEAD] tracks are upcoming.
 *
 * Collections are finite ordered lists; one path serves all three:
 *  - [PlaySource.Liked]: every like, newest first.
 *  - [PlaySource.Playlist]: a YouTube playlist (a Mix link included) in its own order, named by
 *    YouTube's own title once the list lands.
 *  - [PlaySource.Channel]: a channel's uploads, newest first; a channel without a Videos tab (the
 *    auto-generated "- Topic" ones) falls back to a search kept to the channel's own uploads.
 *  Playlist and Channel are the starts that need the network: the old station plays on until the
 *  list lands (a newer start drops it), and [onStationStarted] fires then. A list plays round by
 *  round, in order or shuffled ([setShuffle]); a round's last track decides what follows by the
 *  repeat mode ([cycleRepeat]): ALL and ONE queue the next round (reshuffled when shuffle is on;
 *  under ONE the player loops the track itself, and Next moves on), OFF rolls over as that track
 *  starts - Liked into Liked radio, Playlist and Channel into a song radio from that last track.
 *  Removing a track (unlike in Liked, remove) drops it from later rounds too. A like there acts
 *  by the like mode as above (an unlike in Liked removes the track, owner decision). Liked and
 *  Liked radio with no likes, and a list that came back empty, log and stay idle (the old
 *  station, if any, plays on).
 *
 * A play counts in history once the playhead passes [PLAY_COUNT_MIN_MS] or [PLAY_COUNT_FRACTION]
 * of the track, whichever comes first ([onProgress]), once per play. The history feeds Listen
 * again and Library > History only; the station does not read it.
 *
 * Previous ([previous], owner decision 2026-11-12, Spotify-like) goes back for real. Past
 * [PREVIOUS_RESTART_MS] it restarts the track. Before that it plays the station's previous song
 * again (a pinned extra, with the current song pinned right after it, so Next returns to it);
 * from the station's first song it restores the station the listener left by a station change
 * ([start]): its source, upcoming list, list place, Liked radio round, shuffle and repeat, with
 * the song that was playing when it was left playing again from the start. Up to
 * [STATION_BACK_STACK] stations are kept, so repeated Previous walks back through several
 * changes. A roll-over or a Switch now is the same station going on, not a change.
 *
 * Stations in Room (PLAN "Home v2 and albums"): every station has an id; a [StationSnapshot] of
 * it is written ([snapshots]) when it is left, when a track starts and every [SNAPSHOT_SAVE_MS]
 * of playhead while one plays. The stack above is hydrated from those rows at startup, so
 * Previous reaches a station from before an app restart; Home's Continue lists the rows and
 * [resume] brings one back exactly (its song at its playhead, [startPositionFor]).
 *
 * An album ([PlaySource.Album]) is one long video; its chapters are its tracks. [nextChapter]
 * and [previousChapter] move through them (Previous's 3 s rule inside a chapter), [state] lists
 * them for Up next, and the heart saves or removes the album ([toggleAlbumLike]), never blends.
 * The radio from the album is fetched as it starts; when the album ends (Next on its last
 * chapter, or its end), the station goes on as that radio. That roll-over, and a playlist or
 * channel that ended under repeat off, are announced on [rollOvers] for the Player's chip.
 *
 * Debugging: one `TubeStation` key=value log line per event (`adb logcat -s TubeStation`), and
 * [dump] prints the whole state (PlaybackService routes `dumpsys` to it). The pass criteria for a
 * listening test are in docs/probes/radio-criteria.md and docs/probes/home-v2-criteria.md.
 */
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

    /** A queue slot. [pinned]: Play next or a jump, the listener's own pick; nothing reorders or
     *  replaces it. [blended]: put in by a Blend in like; the next like interleaves with it, a
     *  shuffle leaves it in place, and in a list it is an extra outside the round. Compared by
     *  identity: a collection finds its slots in [round] that way. */
    private class Slot(
        val video: YtVideo,
        val why: Why,
        val pinned: Boolean = false,
        val blended: Boolean = false,
    )

    /** One fetch from [seed] after the shared rules ([songLists]): its Mix and related tracks as
     *  slots, each list in its own order. */
    private class Feed(val seed: YtVideo, val lists: SongLists) {
        val mix = Why(Provenance.MIX, seed.videoId, seed.title).let { why ->
            lists.mix.map { Slot(it, why) }
        }
        val related = Why(Provenance.RELATED, seed.videoId, seed.title).let { why ->
            lists.related.map { Slot(it, why) }
        }

        /** The pairs, then the rest of the longer list ([pairUp]). */
        fun merged(random: Random): List<Slot> = pairUp(mix, related, random)

        /** The log fields for [added], the slots taken from this feed: how many of each list,
         *  which list was empty while the other filled its share, and the copies the
         *  one-copy-per-title rule removed. */
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

    // One lock for everything below: mutated from the loop and the UI (main) and from Mix and
    // related fetches (IO). Sections are short (no network inside the lock). *Locked functions
    // need it held.
    private val lock = Any()

    // --- The station (reset by start) ---
    private var source: PlaySource? = null
    private val queue = mutableListOf<Slot>()
    private var current = -1

    /** This station's id ([StationSnapshot.id]): new at every station change, kept by a
     *  roll-over and a Switch now, and the restored station's own after Previous or [resume].
     *  0 before the first station. */
    private var stationId = 0L

    /** The playhead of the current track, ms, as [onProgress] last reported it. */
    private var positionMs = 0L

    /** [positionMs] at the last write of this station: the next periodic write waits for
     *  [SNAPSHOT_SAVE_MS] of playhead from here. */
    private var persistedAtMs = 0L

    /** A [resume]d station's track and its playhead: the player owner seeks there as that slot
     *  starts ([startPositionFor]), once. */
    private var resumeAt: Pair<Slot, Long>? = null

    /** The chapter playing, while an album with chapters plays its own video; else -1. */
    private var chapterIndex = -1

    /** A playlist, channel or album that ended and goes on as a radio: the roll chip waits for
     *  the first radio track to start ([rollOvers]). */
    private var pendingRollChip: PlaySource? = null

    /** This station's tracks that played before the current one, oldest first, at most
     *  [EARLIER_MAX] (the Earlier rows' bound): what [previous] steps back through. */
    private val history = ArrayDeque<Slot>()

    /** The slot a step back put next: its advance replaces a track that goes back to upcoming,
     *  so that track does not join [history]. */
    private var steppingBackTo: Slot? = null

    /** Removed by the listener from this station's queue: never queued again here. */
    private val dismissedIds = mutableSetOf<String>()

    /** A chain (radio) or a like's fetch (Liked radio) is in flight: no second one. */
    private var chaining = false

    /** The seed of the newest chain, start or Switch now fetch: the song the queue's end
     *  follows. */
    private var chainSeed: YtVideo? = null

    /** Station identity, bumped by every reset: an async fetch for an older station is dropped. */
    private var generation = 0

    /** Bumped by every [start] call: a playlist or channel fetch that lands after a newer start is
     *  dropped. */
    private var startRequests = 0

    private var shuffle = false

    // --- Liked radio (empty in any other source) ---

    /** Likes not yet queued this round, in play order. Empty: the next like starts a new round. */
    private val likesRound = ArrayDeque<YtVideo>()

    /** Likes queued so far this round, for the `round=` log field. */
    private var roundPlayed = 0

    /** Bumped when the queued likes are laid again or Liked radio ends: a like's fetch for an
     *  older layout is dropped. */
    private var fillGeneration = 0

    // --- Collections (Liked, Playlist, Channel; empty in a radio) ---

    /** The list in its own order, minus the tracks the listener removed. Every round is drawn
     *  from it. */
    private val collection = mutableListOf<YtVideo>()

    /** Why each [collection] track plays: its provenance and the list's title. */
    private var collectionWhy: Why? = null

    /** This round's play order, as queue slots: [collection] in order or shuffled. Slots after
     *  [roundPos] are unplayed; they follow the current track and its Play next pins. */
    private val round = mutableListOf<Slot>()

    /** Index in [round] of the list track playing (or last played, while a pin plays); -1 before
     *  the first. */
    private var roundPos = -1

    /** The round after this one, queued once this round's last track plays under repeat ALL or
     *  ONE so the player never runs off the end. Built once: the up next sheet must not reshuffle
     *  under the listener. */
    private var nextRound: List<Slot>? = null

    private var repeat = RepeatMode.OFF

    // --- The session (survives start) ---
    private val playedIds = mutableSetOf<String>()
    private val earlier = ArrayDeque<YtVideo>()
    private var playing: YtVideo? = null
    private var playCounted = false

    /** Stations left by a station change, oldest first, at most [STATION_BACK_STACK]: where
     *  Previous from a station's first song goes. Hydrated from Room at startup. */
    private val backStack = ArrayDeque<StationSnapshot>()

    /** The next new station's id. Starts at the clock so ids from earlier runs stay unique, and
     *  moves past the newest stored id once Room is read. */
    private var nextStationId = System.currentTimeMillis()

    /** The newest [StationSnapshot.savedAtEpochMs] handed out: saves are strictly ordered even
     *  within one millisecond. */
    private var lastSavedAt = 0L

    /** Serializes snapshot writes; [written] (guarded by it) drops a write older than one
     *  already made for the same station. */
    private val writeLock = Mutex()
    private val written = HashMap<Long, Long>()

    private val likedVideos = mutableListOf<YtVideo>() // newest like first
    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())

    /** Liked video ids, for the UI's heart state. */
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    private val _likedAlbumIds = MutableStateFlow<Set<String>>(emptySet())

    /** Liked album ids: the heart's state in album mode and in Library > Albums. */
    val likedAlbumIds: StateFlow<Set<String>> = _likedAlbumIds.asStateFlow()

    private val _state = MutableStateFlow(StationState())

    /** The station for the UI: source, now, earlier, the upcoming queue, shuffle and repeat. */
    val state: StateFlow<StationState> = _state.asStateFlow()

    // A few in flight at most (one per like); a chip nobody shows is not worth suspending for.
    private val _likeEffects = MutableSharedFlow<LikeEffect>(extraBufferCapacity = 4)

    /** What a like did to the queue, once the liked song's fetch landed (Blend in, Switch now).
     *  Nothing for Save only, Songs added per like = 0, or Liked. */
    val likeEffects: SharedFlow<LikeEffect> = _likeEffects.asSharedFlow()

    private val _rollOvers = MutableSharedFlow<RollOver>(extraBufferCapacity = 4)

    /** A playlist, channel or album ended and the station goes on as a radio: emitted as the
     *  first radio track starts, for the "<Album> ended · continuing as Radio" chip. */
    val rollOvers: SharedFlow<RollOver> = _rollOvers.asSharedFlow()

    /** Fired (any thread, outside the lock) once a [start] has replaced the station, so the player
     *  owner restarts its loop at index 0. Not fired for an idle start (no likes) or a playlist or
     *  channel that came back empty: the old station plays on then. */
    @Volatile var onStationStarted: (() -> Unit)? = null

    init {
        // Hydrate likes, album likes and stored stations from disk once (M-C): ids feed the heart
        // state, the videos Liked and Liked radio, the stations Previous's stack. Ms-scale; a like
        // pressed before this lands toggles against an empty set - an accepted race for a window
        // of one DB read.
        scope.launch {
            val persisted = likedTracks.all()
            val albums = likedAlbums.all()
            val stations = snapshots.all()
            synchronized(lock) {
                // A like made before this landed is newer than anything on disk: it stays first.
                val known = likedVideos.mapTo(HashSet()) { it.videoId }
                likedVideos += persisted.filter { it.videoId !in known }
                refreshLikesLocked()
                _likedAlbumIds.value = _likedAlbumIds.value + albums.map { it.videoId }
                hydrateStationsLocked(stations)
                publishLocked()
            }
        }
    }

    // --- Public API ---

    /** Replace the station with [source] (see the class KDoc for each source). */
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

    /** Resume the stored station [id] (a Continue card) exactly as it was left: its source, its
     *  upcoming list, list place and round, shuffle and repeat, and its song at its playhead
     *  ([startPositionFor]). A station change: the station playing is saved first. Unknown or
     *  undecodable ids log and leave the station as it is. */
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

    /** The playhead a [resume]d track starts at, once, when the loop starts queue [index]; null
     *  for every other start (the player owner then plays from 0). */
    fun startPositionFor(index: Int): Long? = synchronized(lock) {
        val (slot, at) = resumeAt ?: return null
        if (queue.getOrNull(index) !== slot) return null
        resumeAt = null
        at.takeIf { it > 0 }
    }

    /** The heart of the Player and the mini-bar on the playing [video]: in album mode, while the
     *  album's own video plays, it saves or removes the album ([toggleAlbumLike]); everywhere
     *  else it is [toggleLike]. */
    fun toggleHeart(video: YtVideo) {
        val album = synchronized(lock) {
            (source as? PlaySource.Album)?.video?.videoId == video.videoId &&
                queue.getOrNull(current)?.why?.provenance == Provenance.ALBUM
        }
        if (album) toggleAlbumLike(video) else toggleLike(video)
    }

    /** Like/unlike the song [video] (normally the playing track) and persist it either way. A
     *  new like acts on the queue by `AppSettings.likeMode` ([likeLocked]) in every source but
     *  Liked; an unlike there changes nothing. In Liked an unlike drops the track from the list
     *  and a like changes nothing. In Liked radio a like also joins this round of likes and an
     *  unlike leaves it. */
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
        likedTracks.set(video, becameLiked) // persist both directions (M-C)
    }

    /** Likes added by a backup import (already persisted), newest first. Startup hydration ran
     *  long ago, so they reach the heart state here. Ids already liked stay where they are. The
     *  new ones go first: a link import dates them now, and in a restore into an empty install
     *  there is nothing to order against; a restart re-reads the exact order from disk. In Liked
     *  radio they join this round of likes. */
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

    /** Save or remove [album] as a liked album (Library > Albums), and persist it. Save only,
     *  whatever "When you like a song" says: an album never blends into the queue. */
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

    /** Albums added by a backup import (already persisted): they reach the heart state here. */
    fun onAlbumsImported(albums: List<YtVideo>) {
        synchronized(lock) {
            _likedAlbumIds.value = _likedAlbumIds.value + albums.map { it.videoId }
            log("albums-imported n=${albums.size}")
        }
    }

    /** Pin [video] right after the current track. Pinned tracks are the listener's choice: a
     *  shuffle or a Switch now never moves them, and the never-replay rule does not apply. A
     *  collection track pinned this way leaves the rest of this round and stays in the list for
     *  the next. */
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

    /** Play [video] now, in place of the playing track: puts it right after the current one and
     *  returns that queue index, for the player owner to advance the loop to; null with no
     *  station. A jump is not a skip.
     *
     *  A queued track moves up from where it was. In a collection the list then goes on from the
     *  jumped-to track's place ([moveRoundToLocked] when it starts), so the tracks between are
     *  passed over for this round, not queued again. In a radio the other queued tracks stay
     *  queued after it. [again] (an Earlier row) plays [video] once more as a pinned extra, after
     *  which the station or the list goes on from where it was. */
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

    /** Previous, at playhead [positionMs] of the playing track (see the class KDoc). Returns the
     *  queue index the player owner advances the loop to, or null: restart the playing track.
     *  Past [PREVIOUS_RESTART_MS], or with nothing earlier, it is a restart. Else the station's
     *  previous song plays again (a jump with again, not a skip), the current one right after
     *  it; from the station's first song the newest station on [backStack] comes back (it may
     *  come from Room, saved before an app restart), which also drops a playlist or channel start
     *  still fetching. In album mode [previousChapter] decides first. */
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

    /** Next in album mode with chapters, at playhead [positionMs]: the next chapter's start, for
     *  the player owner to seek to. Null on the last chapter (the album ends: the loop advances
     *  into the radio) and outside album mode. */
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

    /** Previous in album mode with chapters, at playhead [positionMs]: past
     *  [PREVIOUS_RESTART_MS] into a chapter it restarts that chapter, else it goes to the
     *  previous chapter's start. Returns where to seek; null on the first chapter's first
     *  [PREVIOUS_RESTART_MS] (then [previous] acts as on a song) and outside album mode. */
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

    /** Drop [videoId] from the unplayed queue. This station does not queue it again; a radio
     *  chains if the queue ran low. */
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

    /** In a list, [on] shuffles the unplayed rest of the round; off restores list order from the
     *  playing track's place in the list. In Liked radio, [on] shuffles the likes not yet played
     *  this round and off puts them newest first ([relayLikedRadioLocked]); each new round
     *  follows the setting, and the tracks after each like stay in their pair order. In a
     *  radio, [on] shuffles the upcoming chained tracks among their own places (Blend in tracks
     *  and pins stay where they are) and every chain appended while it stays on; off leaves the
     *  order as it is. The playing track and Play next pins never move. */
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
                nextRound = null // rebuilt in the new order if this round is already over
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

    /** Collections only: repeat OFF -> ALL -> ONE -> OFF. Under ONE the player owner loops the
     *  track ([StationState.repeat]); the queue treats ONE like ALL, so Next past the last track
     *  wraps. Ignored (and logged) in a radio. */
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
            // On a round's last track, OFF now rolls over and ALL/ONE now queue the next round.
            relayCollectionLocked()
            publishLocked()
        }
    }

    /** The playhead of [videoId]; PlaybackService calls this from its position tick. It follows
     *  an album's chapter, writes the station every [SNAPSHOT_SAVE_MS] of playhead, and counts
     *  the play in history once it passes the threshold (item 12), once per play. */
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
        scope.launch { playHistory.record(counted) } // off the loop
    }

    /** The whole station state as plain text, for `dumpsys` and the debug screen. */
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
            // Oldest first, each as <kind>:<the track that comes back>.
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

    // --- MediaSourceProvider ---

    override fun queue(): PlaybackQueue = object : PlaybackQueue {
        override val size: Int get() = synchronized(lock) { queue.size }
        override val currentIndex: Int get() = synchronized(lock) { current }
        override val isRotating: Boolean = false
        override fun idAt(index: Int): String? = synchronized(lock) {
            queue.getOrNull(index)?.video?.videoId
        }
    }

    override suspend fun mediaRequestFor(id: String): MediaRequest {
        // A throw here (YouTubeError: deleted, geo-blocked, bot check...) is the SPI's "skip me"
        // signal - the loop catches it and moves on, bounded by queue size.
        val stream = extractor.resolveAudioStream(id)
        return MediaRequest(stream.url, stream.mimeType)
    }

    override fun onQueueAdvanced(index: Int) {
        val next = synchronized(lock) {
            val slot = queue.getOrNull(index) ?: return
            // The loop re-reports the same index on a stream-URL replay: that is not a new play.
            val newTrack = index != current
            if (newTrack) {
                playing?.let { pushEarlierLocked(it) }
                // The slot that played before this one, unless a step back sent it to upcoming.
                // Nothing after a start: a new station has no history yet.
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

    /** Writes the playing station now (the service is going away). */
    fun persistNow() {
        synchronized(lock) { persistLocked("stop") }
    }

    // --- Starts ---

    /** The two radio starts: [first] plays first, [extras] (the other search results) follow
     *  it, then the pairs of [first]. */
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

    /** The list's own name replaces [source]'s title once it lands: YouTube's name for the
     *  playlist, album or Mix, not the placeholder the start passed (a Mix link starts as
     *  [PlaySource.Playlist.MIX_PLACEHOLDER]). The placeholder stays when YouTube gave no name. */
    private fun startPlaylist(request: Int, source: PlaySource.Playlist) {
        scope.launch {
            val list = runCatching { extractor.playlistInfo(source.url) }
                .onFailure { log("playlist url=${q(source.url)} error=${q(it.message)}") }
                .getOrNull()
            val videos = list?.videos.orEmpty()
            // playlistInfo falls back to the bare id for a nameless list: not a name.
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
            // No Videos tab (the auto-generated "- Topic" channels): search the name and keep the
            // channel's own uploads, or the artist channel of that name.
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

    /** A playlist or channel whose list has landed: it replaces the station unless a newer
     *  [start] superseded [request] or the list is empty. */
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

    /** An album: its chapters come with [source] (a Continue card, a test) or from one fetch of
     *  the video, which also refreshes its metadata. A failed fetch plays the album as one track.
     *  The old station plays on until it lands; a newer start drops it. */
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

    /** The album replaces the station: its video, then (once fetched) the radio from it, which
     *  plays when the album ends. */
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

    /** The album's own video left the player (its end, Next on its last chapter, a pin): the
     *  station goes on as the radio from it, the same station, and the roll chip waits for the
     *  first radio track. */
    private fun albumEndedLocked(album: PlaySource.Album) {
        log("rollover from=album to=song-radio at=${album.video.videoId}")
        source = PlaySource.SongRadio(album.video)
        chapterIndex = -1
        pendingRollChip = album
    }

    /** The chapters while an album plays its own video and has some; else null. */
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

    /** A station change: the station playing goes on [backStack] for Previous, then [source]
     *  replaces it. */
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

    /** Keeps the station playing on [backStack], dropping the oldest past [STATION_BACK_STACK],
     *  and writes it to Room. A station with no track played yet has nothing to come back to. */
    private fun saveStationLocked() {
        val snapshot = snapshotLocked() ?: return
        backStack.addLast(snapshot)
        while (backStack.size > STATION_BACK_STACK) backStack.removeFirst()
        log("snapshot saved reason=left ${describeSnapshot(snapshot)}")
        write(snapshot)
    }

    /** The playing station as a [StationSnapshot] (see there for the slot indices), or null
     *  before its first track. */
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

    /** Writes the playing station to Room: Continue and the stack after a restart read it. */
    private fun persistLocked(reason: String) {
        val snapshot = snapshotLocked() ?: return
        persistedAtMs = positionMs
        // The periodic write is the only silent one: a line per 10 s of playhead is noise.
        if (reason != "progress") log("snapshot saved reason=$reason ${describeSnapshot(snapshot)}")
        write(snapshot)
    }

    /** One Room write, off the lock, in save order per station. */
    private fun write(snapshot: StationSnapshot) {
        scope.launch {
            writeLock.withLock {
                if ((written[snapshot.id] ?: Long.MIN_VALUE) > snapshot.savedAtEpochMs) return@withLock
                written[snapshot.id] = snapshot.savedAtEpochMs
                snapshots.save(snapshot, SNAPSHOTS_KEPT)
            }
        }
    }

    /** Stations from Room (newest first) under the ones this run already left: the stack
     *  Previous walks after an app restart. */
    private fun hydrateStationsLocked(rows: List<StationSnapshot>) {
        val known = backStack.mapTo(HashSet()) { it.id }.apply { add(stationId) }
        val older = rows.filter { it.id !in known }.take(STATION_BACK_STACK).reversed()
        backStack.addAll(0, older)
        while (backStack.size > STATION_BACK_STACK) backStack.removeFirst()
        rows.maxOfOrNull { it.id }?.let { nextStationId = maxOf(nextStationId, it + 1) }
        rows.maxOfOrNull { it.savedAtEpochMs }?.let { lastSavedAt = maxOf(lastSavedAt, it) }
        log("snapshot hydrated rows=${rows.size} stack=${backStack.size}")
    }

    /** [saved] is the station again, with its own id: its left track first (the loop advances
     *  to index 0), then its upcoming list, its history, list place, Liked radio round, shuffle
     *  and repeat. Each stored slot becomes one slot again, so the queue and the rounds share
     *  them as before. A chain or a like's fetch that was in flight when it was left was dropped
     *  then; the queue grows again at the next advance ([growLocked]). */
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

    // --- Radio ---

    /** Grows the queue after an advance or a removal: a radio chains below [CHAIN_BELOW], Liked
     *  radio tops up its slots. Lists grow by their rounds instead ([relayCollectionLocked]). */
    private fun growLocked() {
        when (val src = source) {
            is PlaySource.SongRadio, is PlaySource.SearchRadio -> {
                val last = playing ?: return
                if (!chaining && upcomingLocked() < CHAIN_BELOW) chainLocked(last, "low")
            }
            // The radio after the album, again if the start's fetch brought too little.
            is PlaySource.Album ->
                if (!chaining && upcomingLocked() < CHAIN_BELOW) chainLocked(src.video, "album")
            PlaySource.LikedRadio -> fillLikedRadioLocked()
            is PlaySource.Liked, is PlaySource.Playlist, is PlaySource.Channel, null -> Unit
        }
    }

    /** Appends the pairs of [seed] (shuffled while shuffle is on): all of its Mix and its related
     *  list. */
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

    /** A new like in [src] (any source but Liked), by `AppSettings.likeMode`; Blend in takes
     *  `AppSettings.songsPerLike` tracks, both read now. */
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
                // A song or search radio moves an upcoming track up; a list or Liked radio
                // takes tracks not yet queued, so the list and each like's tracks stay whole.
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

    /** Blend in: [picks] go right after the current track and its Play next pins, interleaved
     *  with what earlier Blend in likes left there (the newest like first). A track already
     *  upcoming moves up from its old place. In a list they stay outside the round
     *  ([relayCollectionLocked] keeps them). */
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

    /** Switch now: everything upcoming but the Play next pins becomes [picks], the liked song's
     *  pairs. A list or Liked radio leaves its source for a song radio from [liked] (shuffle off,
     *  as a new radio), which chains from then on. */
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

    /** Shuffles the upcoming Mix and related tracks among their own places; returns how many. */
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

    /** Fetches [seed]'s Mix and related videos off the loop, both at once, then runs [land]
     *  under the lock with the two lists after the shared rules ([songLists]; [skipQueued]: see
     *  [excludedIdsLocked]). A failed fetch counts as an empty list. A fetch for a station that
     *  was replaced meanwhile is logged and dropped. [what] names the caller in those log
     *  lines. */
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

    /** The ids no fetch may queue: songs played this session, songs the listener removed and,
     *  while [skipQueued], songs already upcoming. In Liked radio the likes are left out too:
     *  they have their own place in the rounds. */
    private fun excludedIdsLocked(skipQueued: Boolean): MutableSet<String> {
        val ids = HashSet<String>(playedIds)
        ids += dismissedIds
        if (skipQueued) queue.mapTo(ids) { it.video.videoId }
        if (source == PlaySource.LikedRadio) ids += _likedIds.value
        return ids
    }

    /** The search radio's other results: [videos] minus [first], the excluded ids
     *  ([excludedIdsLocked]), durations outside the window and repeats. */
    private fun freshLocked(videos: List<YtVideo>, first: YtVideo): List<YtVideo> {
        val known = excludedIdsLocked(skipQueued = true).apply { add(first.videoId) }
        return videos.filter { it.inTrackWindow() && known.add(it.videoId) }
    }

    // --- Liked radio ---

    /** Ends this Liked radio's rounds; a like's fetch in flight is dropped when it lands. */
    private fun clearLikedRadioLocked() {
        likesRound.clear()
        roundPlayed = 0
        fillGeneration++
    }

    /** Liked radio from here: on start, or on the Liked roll-over, which keeps the queue so the
     *  first like of the new round is not the track that just played. Shuffle is on by default
     *  here (owner decision 2026-11-11). */
    private fun enterLikedRadioLocked() {
        source = PlaySource.LikedRadio
        clearLikedRadioLocked()
        shuffle = true
        fillLikedRadioLocked()
    }

    /** Queues Liked radio units - a like, then the first `AppSettings.songsPerLike` tracks of its
     *  pairs - while fewer than [LIKED_RADIO_LOOKAHEAD] tracks are upcoming. One unit at a time:
     *  the next like waits until this like's fetch landed, so its tracks follow their own like.
     *  The fetch lands in seconds and the queue grows as a track starts, minutes before the next
     *  is needed. A fetch with nothing new adds no tracks and the next like follows, so the
     *  player never runs dry; each step queues one like, so the fetches end at the lookahead. */
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

    /** The next like of this round; a new round (all likes, shuffled while shuffle is on, else
     *  newest first) once it is spent. The like queued last does not open the next round. */
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

    /** Shuffle changed in Liked radio: the likes not yet played this round take the new order
     *  (shuffled, or newest first), and the queue after the current like's tracks is laid
     *  again from them. Pins and Blend in tracks stay. A queued like that already opened the
     *  next round merges back into this one. Returns the likes re-ordered. */
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
            // The fetch in flight, if any, was for a like just taken back.
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

    /** A like Liked radio's rounds queued (not a pin or a blend). */
    private val Slot.isRoundLike: Boolean
        get() = why.provenance == Provenance.LIKED && !pinned && !blended

    private fun logLikedRadio(like: YtVideo, added: String) {
        log(
            "liked-radio like=${like.videoId} title=${q(like.title)} " +
                "round=$roundPlayed/${roundPlayed + likesRound.size} $added",
        )
    }

    // --- Collections ---

    /** Replaces the station with [tracks] as a collection, the first round in order or shuffled. */
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

    /** The whole list as a fresh round: in order, or freshly shuffled. */
    private fun newRoundLocked(): List<Slot> {
        val order = if (shuffle) collection.shuffled(random) else collection
        return order.map(::collectionSlotLocked)
    }

    /** Points [roundPos] at [slot]: in this round, or the first slot of [nextRound] (the wrap). */
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

    /** Re-lays a collection's queue after the playing track: Play next pins and Blend in extras
     *  (in their order), then the unplayed rest of the round. Once the round's last track plays,
     *  the repeat mode decides what follows it - the next round (ALL, ONE) or the roll-over into
     *  a radio (OFF) - so the player never runs off the end. Called after every change to the
     *  round, the order or the repeat mode. */
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

    /** A round ended under repeat OFF: the station rolls on while its last track plays, keeping
     *  the queue so the never-replay rule sees what just played. Liked goes on as Liked radio, a
     *  playlist or channel as a song radio from that last track; their roll chip waits for the
     *  first radio track. */
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

    /** Removes every unplayed copy of [videoId] (never the current track). Returns the count. In
     *  a collection the track also leaves the rest of the round and, while [leaveList], the list
     *  itself, so later rounds skip it too. */
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

    // --- State and logging ---

    /** Tracks queued after the current one (all of them before the first advance). */
    private fun upcomingLocked(): Int = queue.size - 1 - current

    /** Warm the stream cache for the next queued track, so advancing is instant. Failures are
     *  fine - the real resolve at advance time will surface them as skip-me. */
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

    // The snapshot is assigned under the lock, so snapshots land in mutation order: a slow
    // publisher can never overwrite a newer state with an older one.
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

    // Call under the lock (reads the liked list).
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

    /** The source's word in `like`, `switch`, `rollover` and `previous` log lines and the dump. */
    private fun kind(source: PlaySource): String = when (source) {
        is PlaySource.SongRadio -> "song"
        is PlaySource.SearchRadio -> "search"
        is PlaySource.Playlist -> "playlist"
        is PlaySource.Channel -> "channel"
        is PlaySource.Liked -> "liked"
        PlaySource.LikedRadio -> "liked-radio"
        is PlaySource.Album -> "album"
    }

    /** A stored station in one line: `snapshot` log lines and the dump. */
    private fun describeSnapshot(saved: StationSnapshot): String = "station=${saved.id} source=${kind(saved.source)} id=${saved.leftVideo.videoId} " +
        "pos=${saved.positionMs} upcoming=${saved.upcoming.size} history=${saved.history.size} " +
        "round=${saved.roundPos + 1}/${saved.round.size} shuffle=${saved.shuffle} " +
        "repeat=${saved.repeat} title=${q(saved.leftVideo.title)}"

    /** The seed of the Mix (or related list) [slot] came from, or `-`. */
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

        /** Chain the next pairs when fewer than this many tracks are upcoming. GUESS: 5 (~17 min
         *  at ~3.5 min a track), unmeasured. A chain is one fetch (seconds), so this is margin
         *  for a slow or failed fetch and for unresolvable tracks the loop skips. Lower: a failed
         *  fetch can drain the queue and the loop waits ("Looking for the next track"). Higher:
         *  the chain fires earlier, from a song further back in the queue, with no gain the
         *  listener hears. Internal for tests. */
        internal const val CHAIN_BELOW = 5

        /** Liked radio tracks queued ahead of the current one. GUESS: 6, unmeasured - two whole
         *  units (a like and its tracks) at the default of 2 Songs added per like, so Up next
         *  shows the like after the next. Each unit queued is one fetch, so higher fetches
         *  further ahead and delays a Songs added per like change; lower shows less of what is
         *  coming and leaves less margin for a slow fetch. Internal for tests. */
        internal const val LIKED_RADIO_LOOKAHEAD = 6

        /** Track duration window, seconds. GUESSES: under 60s is jingles/shorts/teasers; over
         *  15min is the 10-hour-lofi-compilation shape that would hijack a song station. Neither
         *  bound has been validated against real station output. Internal because Home's
         *  "More like" rows apply the same window ([inTrackWindow], station/Pairs.kt). */
        internal const val MIN_TRACK_SECONDS = 60L
        internal const val MAX_TRACK_SECONDS = 15 * 60L

        /** A play counts in history after this much of it, ms - or [PLAY_COUNT_FRACTION] of the
         *  track if that comes first (PLAN item 12). GUESS: 30 s, the usual "counts as a stream"
         *  convention, not measured here. Lower counts drive-by skips as plays (pollutes Listen
         *  again); higher misses real short listens. */
        internal const val PLAY_COUNT_MIN_MS = 30_000L

        /** GUESS: 0.5 - the other half of item 12: a short track counts after half of it, so a
         *  40 s track counts at 20 s instead of never. */
        internal const val PLAY_COUNT_FRACTION = 0.5

        /** Earlier rows kept for [StationState], and the songs Previous can step back through in
         *  one station ([history]). Display and memory bound, not tuned: ~3 h of listening, more
         *  than anyone scrolls back in the up next sheet or walks back by Previous. */
        private const val EARLIER_MAX = 50

        /** Rows [dump] prints for earlier plays. Debug display only. */
        private const val DUMP_EARLIER = 10

        /** Previous restarts the playing track past this playhead, ms; at or below it, it goes
         *  back a song or a station. GUESS: 3 s, taken as the usual music-player convention (our
         *  inference, not checked against any player), not measured here. Lower: a listener who
         *  presses Previous to hear the start again jumps back a song instead. Higher: a listener
         *  a few seconds into a song they skipped to by mistake restarts it instead of going
         *  back. Internal for tests. */
        internal const val PREVIOUS_RESTART_MS = 3_000L

        /** Stations Previous can go back to. GUESS: 5, unmeasured - more station changes than a
         *  listener walks back through by pressing Previous. Each holds its upcoming list (up to
         *  ~50 slots) and history (up to [EARLIER_MAX]), so the bound is also memory. Lower:
         *  Previous runs out of stations sooner and restarts. Higher: costs memory nobody walks
         *  back to. Internal for tests. */
        internal const val STATION_BACK_STACK = 5

        /** While a track plays, the station is written to Room each time the playhead moved this
         *  far from the last write, ms (and at every track start and station change). GUESS:
         *  10 s, unmeasured - the most a Continue card can be behind after the process is killed
         *  (home-v2-criteria.md section 1). Lower: more Room writes for a few seconds of
         *  accuracy. Higher: a killed app resumes further back. Internal for tests. */
        internal const val SNAPSHOT_SAVE_MS = 10_000L

        /** Stations Room keeps: the most Continue cards ([AppSettings.MAX_CONTINUE_CARDS]) plus
         *  the playing station's own row, which Continue leaves out. Also bounds the stack a
         *  restart can rebuild ([STATION_BACK_STACK] is below it). */
        private const val SNAPSHOTS_KEPT = AppSettings.MAX_CONTINUE_CARDS + 1
    }
}

/** The chapter playing at [positionMs]: the last one starting at or before it (the first one
 *  before any start). */
internal fun chapterAt(chapters: List<YtChapter>, positionMs: Long): Int = chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
