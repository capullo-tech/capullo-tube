package tech.capullo.tubecapullo.station

import tech.capullo.audio.contracts.MediaRequest
import tech.capullo.audio.contracts.MediaSourceProvider

/**
 * The station's advance loop: `queue().idAt(i) → mediaRequestFor(id) → play → onQueueAdvanced(i)`.
 *
 * DOCUMENTED COPY - the master is `tech.capullo.audio.QueuePlaybackLoop`, which is `internal` to
 * capullo-audio and unreachable from an app (HANDOFF-bootstrap §3.1/§3.3). When the engine's facade
 * exposes its loop, DELETE this class and wire the engine instead; until then the two must honour
 * the same contract obligations, verified here by StationLoopTest:
 *
 *  1. **Ordering.** `mediaRequestFor(idAt(i))` is called BEFORE `onQueueAdvanced(i)` - structurally,
 *     in the single [resolveAndPlay] method - because the source's prefetch decisions assume the
 *     current track already resolved.
 *  2. **Skip on throw.** A throw from `mediaRequestFor` is the source's only "skip me" signal; it is
 *     caught and the track skipped, bounded by the queue size so an all-unresolvable queue can't
 *     spin forever.
 *  3. **Clock overlay** is the player owner's job: the source only knows metadata; live
 *     `isPlaying`/position are overlaid by PlaybackService before publishing (its
 *     `playerIsPlaying` mirror).
 */
class StationLoop(
    private val source: MediaSourceProvider,
    private val playback: Playback,
) {
    /** The one thing the loop needs from a player. PlaybackService implements it with ExoPlayer;
     *  tests implement it with a recorder. */
    fun interface Playback {
        fun play(request: MediaRequest)
    }

    /** Fired after a successful advance, with the queue index now playing. */
    var onTrackChanged: ((index: Int) -> Unit)? = null

    /** Fired for every track the source refused (the "skip me" throw), so the owner can log and
     *  tell the user why - a silent skip is a bug (CONTRIBUTING §4). */
    var onResolveFailed: ((index: Int, id: String, error: Exception) -> Unit)? = null

    /** Fired when the queue ran out (end reached, or every remaining track was unresolvable). The
     *  loop then waits at that index: a refill that lands later grows the queue, and
     *  [resumeIfWaiting] picks up from there. Without the wait, a seed that fails to resolve
     *  before its own refill lands leaves the station silent for good (2026-11-11, owner report:
     *  a Trending card stuck on loading). */
    var onQueueExhausted: (() -> Unit)? = null

    /** Queue index currently playing, or -1 before the first successful advance. */
    var currentIndex: Int = -1
        private set

    /** Where the loop stopped for lack of a playable track, or null while it is not waiting. */
    var waitingAt: Int? = null
        private set

    /** Bumped by every advance request. A resolve that returns after a newer request started is
     *  stale (the user tapped again, or skipped) and must not play over the newer choice. */
    private var generation = 0

    suspend fun start() = advanceTo(source.queue().currentIndex.coerceAtLeast(0))

    suspend fun advance() = advanceTo(currentIndex + 1)

    /** Re-resolve and replay the current track (stream-URL-expired recovery). */
    suspend fun replayCurrent() {
        if (currentIndex >= 0) advanceTo(currentIndex)
    }

    /** Continue from [waitingAt] once the queue holds a track there. True when it resumed. */
    suspend fun resumeIfWaiting(): Boolean {
        val at = waitingAt ?: return false
        if (source.queue().idAt(at) == null) return false
        advanceTo(at)
        return true
    }

    suspend fun advanceTo(index: Int) {
        val request = ++generation
        waitingAt = null
        val q = source.queue()
        var i = index
        var attempts = 0
        while (attempts < q.size) {
            val id = q.idAt(i) ?: break // end of the (currently) finite queue
            when (resolveAndPlay(request, i, id)) {
                Outcome.PLAYED, Outcome.STALE -> return
                Outcome.FAILED -> {
                    i++
                    attempts++
                }
            }
        }
        if (request != generation) return
        waitingAt = i
        onQueueExhausted?.invoke()
    }

    private enum class Outcome { PLAYED, FAILED, STALE }

    private suspend fun resolveAndPlay(request: Int, index: Int, id: String): Outcome {
        val media = try {
            source.mediaRequestFor(id) // may suspend (stream resolution)
        } catch (e: Exception) {
            if (request != generation) return Outcome.STALE
            onResolveFailed?.invoke(index, id, e)
            return Outcome.FAILED // the source's only "skip me" signal
        }
        if (request != generation) return Outcome.STALE
        playback.play(media)
        currentIndex = index
        source.onQueueAdvanced(index) // AFTER mediaRequestFor - the source may prefetch N+1
        onTrackChanged?.invoke(index)
        return Outcome.PLAYED
    }
}
