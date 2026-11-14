package tech.capullo.tubecapullo.station

import tech.capullo.audio.contracts.MediaRequest
import tech.capullo.audio.contracts.MediaSourceProvider

class StationLoop(
    private val source: MediaSourceProvider,
    private val playback: Playback,
) {
    fun interface Playback {
        fun play(request: MediaRequest)
    }

    var onTrackChanged: ((index: Int) -> Unit)? = null

    var onResolveFailed: ((index: Int, id: String, error: Exception) -> Unit)? = null

    var onQueueExhausted: (() -> Unit)? = null

    var currentIndex: Int = -1
        private set

    var waitingAt: Int? = null
        private set

    private var generation = 0

    suspend fun start() = advanceTo(source.queue().currentIndex.coerceAtLeast(0))

    suspend fun advance() = advanceTo(currentIndex + 1)

    suspend fun replayCurrent() {
        if (currentIndex >= 0) advanceTo(currentIndex)
    }

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
            val id = q.idAt(i) ?: break
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
            source.mediaRequestFor(id)
        } catch (e: Exception) {
            if (request != generation) return Outcome.STALE
            onResolveFailed?.invoke(index, id, e)
            return Outcome.FAILED
        }
        if (request != generation) return Outcome.STALE
        playback.play(media)
        currentIndex = index
        source.onQueueAdvanced(index)
        onTrackChanged?.invoke(index)
        return Outcome.PLAYED
    }
}
