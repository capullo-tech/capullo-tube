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

    /** Fired when the queue ran out (end reached, or every remaining track was unresolvable). For
     *  a healthy station this means the pool refill lost the race with playback - rare, and the
     *  refill's own appendFromPool heals the queue; the owner decides whether to retry. */
    var onQueueExhausted: (() -> Unit)? = null

    /** Queue index currently playing, or -1 before the first successful advance. */
    var currentIndex: Int = -1
        private set

    suspend fun start() = advanceTo(source.queue().currentIndex.coerceAtLeast(0))

    suspend fun advance() = advanceTo(currentIndex + 1)

    /** Re-resolve and replay the current track (stream-URL-expired recovery). */
    suspend fun replayCurrent() {
        if (currentIndex >= 0) advanceTo(currentIndex)
    }

    suspend fun advanceTo(index: Int) {
        val q = source.queue()
        if (q.size == 0) return
        var i = index
        var attempts = 0
        while (attempts < q.size) {
            val id = q.idAt(i) ?: break // end of the (currently) finite queue
            if (resolveAndPlay(i, id)) return
            i++
            attempts++
        }
        onQueueExhausted?.invoke()
    }

    private suspend fun resolveAndPlay(index: Int, id: String): Boolean {
        val request = try {
            source.mediaRequestFor(id) // may suspend (stream resolution)
        } catch (e: Exception) {
            return false // the source's only "skip me" signal
        }
        playback.play(request)
        currentIndex = index
        source.onQueueAdvanced(index) // AFTER mediaRequestFor - the source may prefetch N+1
        onTrackChanged?.invoke(index)
        return true
    }
}
