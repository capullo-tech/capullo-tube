package tech.capullo.tubecapullo.station

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.capullo.audio.contracts.MediaRequest
import tech.capullo.audio.contracts.MediaSourceProvider
import tech.capullo.audio.contracts.PlaybackQueue
import tech.capullo.tubecapullo.data.history.LikedTracks
import tech.capullo.tubecapullo.data.history.PlayHistory
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtVideo
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * The station: a seed song grows into an endless queue, fed by the candidate pool.
 *
 * Implements the contracts SPI ([MediaSourceProvider]) so the station is already the shape a
 * `capullo-source-youtube` library would export (HANDOFF-bootstrap §1). The queue is FINITE AND
 * GROWING - `isRotating = false`, because rotating means "live stream, never auto-advance" in the
 * engine's semantics (§3.4), and growth happens in [onQueueAdvanced] before the player needs the
 * next id.
 *
 * Pool feeds, per the RD probe (docs/probes/rd-mix-results.md, run 2026-08-18):
 *  - `mix(id)`: YouTube's own song-radio, ~50-60 unique tracks, seed arrives as item #1 (dropped
 *    here, or the station would open by replaying the seed).
 *  - `relatedStreams(id)`: per-video neighbours, nearly disjoint from the mix (5-11% overlap) -
 *    which is why both feeds stay first-class.
 *
 * A played id never returns: [playedIds] filters candidates - and it survives [startStation], so
 * a search is a HARD JUMP (new seed, new pool) that still never replays this session's tracks.
 *
 * Taste (M5, session-scoped - nothing persists across app restarts yet):
 *  - [toggleLike] boosts every candidate from the liked track's neighbourhood and immediately
 *    re-seeds a mix FROM it, flooding the pool with more of what was liked.
 *  - [noteSkip] (fired on user-initiated next, NOT on natural track end) damps the skipped track's
 *    neighbourhood and its uploader. A skip is a weak signal - people also skip for variety.
 */
@Singleton
class StationEngine @Inject constructor(
    private val extractor: YouTubeExtractorClient,
    private val scope: CoroutineScope,
    private val playHistory: PlayHistory,
    private val likedTracks: LikedTracks,
) : MediaSourceProvider {

    data class Candidate(val video: YtVideo, val source: Source, val fromVideoId: String) {
        enum class Source { MIX, RELATED }
    }

    // One lock for queue/pool/played: mutated from the loop (main) and refill (IO). All sections
    // are short (no network inside the lock).
    private val lock = Any()
    private val queue = mutableListOf<YtVideo>()
    private val playedIds = mutableSetOf<String>()
    private val pool = mutableListOf<Candidate>()

    /** Provenance of tracks appended from the pool, so [replanUpcoming] can return an unplayed
     *  queue tail to the pool without losing where each candidate came from. */
    private val queuedProvenance = mutableMapOf<String, Candidate>()
    private var current = -1
    private var refilling = false

    // Taste. Likes persist across restarts (M-C, hydrated in init); skips stay session-scoped.
    // Uploader sets catch resemblance across neighbourhoods (the only similarity signal the
    // metadata carries). All mutated under [lock].
    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    private val skippedIds = mutableSetOf<String>()
    private val likedUploaders = mutableSetOf<String>()
    private val skippedUploaders = mutableSetOf<String>()

    /** Liked video ids, for the UI's heart state. */
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()

    init {
        // Hydrate likes from disk once (M-C): ids feed the heart state, uploaders feed the nudge.
        // Ms-scale; a like pressed before this lands would toggle against an empty set - an
        // accepted race for a window of one DB read.
        scope.launch {
            val persisted = likedTracks.all()
            synchronized(lock) {
                _likedIds.value = _likedIds.value + persisted.map { it.videoId }
                persisted.forEach { it.uploader?.let { u -> likedUploaders += u } }
            }
        }
    }

    /** Jump to a new station seeded from [seed]: clears queue and pool but KEEPS the session's
     *  played history and taste, so a jump never causes a replay and likes keep steering. Instant -
     *  the feeds fill async while the seed itself resolves and plays. */
    fun startStation(seed: YtVideo) {
        synchronized(lock) {
            queue.clear()
            pool.clear()
            queuedProvenance.clear()
            current = -1
            queue += seed
        }
        refill(seed.videoId)
    }

    /** Like/unlike [videoId] (normally the playing track). A new like re-seeds a mix from the
     *  liked track immediately - the pool floods with its neighbourhood without waiting for the
     *  low-water refill. Unlike only removes the boost; it does not claw back candidates. */
    fun toggleLike(videoId: String) {
        var video: YtVideo? = null
        val becameLiked = synchronized(lock) {
            video = queue.find { it.videoId == videoId }
            val uploader = video?.uploader
            if (videoId in _likedIds.value) {
                _likedIds.value -= videoId
                uploader?.let { likedUploaders -= it }
                false
            } else {
                _likedIds.value += videoId
                uploader?.let { likedUploaders += it }
                true
            }
        }
        // Persist both directions (M-C) - the full video carries the row's metadata.
        video?.let { likedTracks.set(it, becameLiked) }
        // A taste change must reach the EAR quickly: the queue already holds a lookahead picked
        // under the old scores, so re-plan it - for a new like, after the re-seeded mix lands (the
        // boosted neighbourhood isn't in the pool until then); for an unlike, immediately.
        if (becameLiked) refill(videoId, replanAfter = true) else replanUpcoming()
    }

    /** A user-initiated skip of [videoId]. Weak negative: damps the track's neighbourhood and
     *  uploader. Callers decide what counts as a skip - natural track end must NOT. */
    fun noteSkip(videoId: String) {
        synchronized(lock) {
            skippedIds += videoId
            queue.find { it.videoId == videoId }?.uploader?.let { skippedUploaders += it }
        }
        replanUpcoming() // the damp must affect the tracks already queued, not just future picks
    }

    fun videoAt(index: Int): YtVideo? = synchronized(lock) { queue.getOrNull(index) }

    // --- MediaSourceProvider ---

    override fun queue(): PlaybackQueue = object : PlaybackQueue {
        override val size: Int get() = synchronized(lock) { queue.size }
        override val currentIndex: Int get() = synchronized(lock) { current }
        override val isRotating: Boolean = false
        override fun idAt(index: Int): String? = synchronized(lock) { queue.getOrNull(index)?.videoId }
    }

    override suspend fun mediaRequestFor(id: String): MediaRequest {
        // A throw here (YouTubeError: deleted, geo-blocked, bot check...) is the SPI's "skip me"
        // signal - the loop catches it and moves on, bounded by queue size.
        val stream = extractor.resolveAudioStream(id)
        return MediaRequest(stream.url, stream.mimeType)
    }

    override fun onQueueAdvanced(index: Int) {
        val playing = synchronized(lock) {
            current = index
            queue.getOrNull(index)?.also { playedIds += it.videoId }
        } ?: return
        playHistory.record(playing) // fire-and-forget bookkeeping (M-B); never stalls the loop
        appendFromPool()
        maybeRefill(playing.videoId)
        prefetch(index + 1)
    }

    // --- Internals ---

    /** Keep [LOOKAHEAD] unplayed tracks queued ahead of the current one. */
    private fun appendFromPool() {
        synchronized(lock) {
            while (queue.size - 1 - current < LOOKAHEAD) {
                val next = takeBestCandidate() ?: break
                queue += next.video
                queuedProvenance[next.video.videoId] = next
            }
        }
    }

    // Highest score wins; the jitter keeps a station from being a deterministic walk of one feed.
    // Call only under [lock].
    private fun takeBestCandidate(): Candidate? {
        val best = pool.maxByOrNull { score(it) } ?: return null
        pool.remove(best)
        return best
    }

    private fun score(c: Candidate): Double {
        var s = when (c.source) {
            // GUESS: equal weights. The probe proved the feeds are complementary (5-11% overlap),
            // not which one yields better next-tracks - that takes listening, not fetching. The
            // taste signal below is what moves selection on evidence.
            Candidate.Source.MIX -> 1.0
            Candidate.Source.RELATED -> 1.0
        }
        if (c.fromVideoId in _likedIds.value) s += LIKE_NEIGHBOURHOOD_BOOST
        if (c.fromVideoId in skippedIds) s -= SKIP_NEIGHBOURHOOD_DAMP
        c.video.uploader?.let {
            if (it in likedUploaders) s += UPLOADER_NUDGE
            if (it in skippedUploaders) s -= UPLOADER_NUDGE
        }
        return s + Random.nextDouble() * SELECTION_JITTER
    }

    /** Return the unplayed queue tail to the pool and re-pick it under the CURRENT scores. This is
     *  what makes a like/skip audible on the very next track instead of after the stale lookahead
     *  drains (~2 tracks ≈ 7 minutes). The explicit seed (index 0, no provenance) is never
     *  removed - it was a user choice, not a scored pick. */
    private fun replanUpcoming() {
        synchronized(lock) {
            while (queue.size - 1 > maxOf(current, 0)) {
                val candidate = queuedProvenance.remove(queue.last().videoId) ?: break
                queue.removeAt(queue.size - 1)
                pool += candidate
            }
        }
        appendFromPool()
    }

    /** Refetch both feeds from [fromId] when the pool runs low - the station drifts with what is
     *  actually playing, which is the "algorithm chooses for you" behaviour before any taste
     *  signal. */
    private fun maybeRefill(fromId: String) {
        val start = synchronized(lock) {
            if (pool.size >= REFILL_BELOW || refilling) return
            refilling = true
            true
        }
        if (start) refill(fromId)
    }

    private fun refill(fromId: String, replanAfter: Boolean = false) {
        scope.launch {
            val mix = runCatching { extractor.mix(fromId) }.getOrDefault(emptyList())
            val related = runCatching { extractor.relatedStreams(fromId) }.getOrDefault(emptyList())
            synchronized(lock) {
                addCandidates(mix, Candidate.Source.MIX, fromId)
                addCandidates(related, Candidate.Source.RELATED, fromId)
                refilling = false
            }
            // A slow refill can race the queue running dry (song ends inside the fetch window):
            // top the lookahead back up now that there is material. A like-triggered refetch
            // re-plans instead, so the boosted neighbourhood displaces the stale lookahead.
            if (replanAfter) replanUpcoming() else appendFromPool()
        }
    }

    // Call only under [lock].
    private fun addCandidates(videos: List<YtVideo>, source: Candidate.Source, fromId: String) {
        val known = playedIds + queue.map { it.videoId } + pool.map { it.video.videoId }
        pool += videos
            .asSequence()
            .filter { it.videoId != fromId } // probe: a mix's first item is its own seed
            .filter { it.videoId !in known }
            .filter { it.durationSeconds in MIN_TRACK_SECONDS..MAX_TRACK_SECONDS }
            .map { Candidate(it, source, fromId) }
    }

    /** Warm the stream cache for the next queued track, so advancing is instant. Failures are
     *  fine - the real resolve at advance time will surface them as skip-me. */
    private fun prefetch(index: Int) {
        val next = synchronized(lock) { queue.getOrNull(index) } ?: return
        scope.launch { runCatching { extractor.resolveAudioStream(next.videoId) } }
    }

    private companion object {
        /** Unplayed tracks kept queued ahead of the current one. GUESS: 2 - enough that one
         *  unresolvable track doesn't stall the station, small enough that a future taste signal
         *  (M5) still steers quickly. Untuned. */
        const val LOOKAHEAD = 2

        /** Refetch feeds when the pool drops below this. GUESS: 10 - one refill yields ~60-70
         *  candidates (probe), so this triggers roughly once per hour of listening. Untuned. */
        const val REFILL_BELOW = 10

        /** Candidate duration window, seconds. GUESSES: under 60s is jingles/shorts/teasers; over
         *  15min is the 10-hour-lofi-compilation shape that would hijack a song station. Neither
         *  bound has been validated against real station output. */
        const val MIN_TRACK_SECONDS = 60L
        const val MAX_TRACK_SECONDS = 15 * 60L

        /** Random spread added to candidate scores for variety. GUESS: 0.3 - enough to interleave
         *  equally-weighted feeds, small enough that the taste boosts below dominate it. Untuned. */
        const val SELECTION_JITTER = 0.3

        /** GUESS: 1.0 - sized to DOMINATE deterministically: liked-neighbourhood minimum
         *  (1.0 + 1.0 + 0) beats neutral maximum (1.0 + 0.3 jitter), so a like visibly changes the
         *  very next pick. If listening shows likes railroading the station, shrink toward the
         *  jitter; below 0.3 a like stops being reliably audible. */
        const val LIKE_NEIGHBOURHOOD_BOOST = 1.0

        /** GUESS: 0.7 - damped maximum (1.0 - 0.7 + 0.3 jitter) sits below neutral minimum (1.0),
         *  so a skipped neighbourhood only returns once nothing neutral is left. Deliberately NOT
         *  1.0+: a skip is a weak signal (variety-skips exist) and the pool should recover. */
        const val SKIP_NEIGHBOURHOOD_DAMP = 0.7

        /** GUESS: 0.2 - an uploader-level nudge below the jitter: shades selection toward/away
         *  from an artist without overriding the per-track signal. */
        const val UPLOADER_NUDGE = 0.2
    }
}
