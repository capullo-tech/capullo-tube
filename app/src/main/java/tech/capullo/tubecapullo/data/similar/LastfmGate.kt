package tech.capullo.tubecapullo.data.similar

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why Last.fm is not asked right now; shown in Settings. */
data class LastfmStatus(
    /** After a rate-limit answer (error 29), no call goes out before this time. */
    val pausedUntilMs: Long = 0,
    /** Keys Last.fm called invalid (10) or suspended (26) this session. */
    val rejectedKeys: Map<String, Int> = emptyMap(),
)

class LastfmPaused(reason: String) : Exception(reason)

/**
 * Keeps this phone under Last.fm's per-IP limit. The terms no longer publish a number; the
 * widely cited one is 5 requests a second averaged over 5 minutes, and Last.fm sends no rate
 * headers, so the gate spaces calls, backs off after error 29 and drops a rejected key. It
 * applies to the built-in key and a personal key alike: the limit counts the IP, not the key.
 */
class LastfmGate(private val now: () -> Long) {
    private val mutex = Mutex()
    private var lastCallMs = Long.MIN_VALUE / 2
    private val _status = MutableStateFlow(LastfmStatus())
    val status: StateFlow<LastfmStatus> = _status.asStateFlow()

    /** Waits for a free slot, or throws [LastfmPaused] when no call may go out with [apiKey]. */
    suspend fun acquire(apiKey: String) {
        mutex.withLock {
            check(apiKey)
            val wait = lastCallMs + MIN_GAP_MS - now()
            if (wait > 0) delay(wait)
            lastCallMs = maxOf(now(), lastCallMs + MIN_GAP_MS)
        }
    }

    private fun check(apiKey: String) {
        val state = _status.value
        state.rejectedKeys[apiKey]?.let { throw LastfmPaused("key-rejected=$it") }
        if (now() < state.pausedUntilMs) throw LastfmPaused("rate-limited")
    }

    /** Records what an answer's error code says about the limit or the key. */
    fun onAnswer(apiKey: String, errorCode: Int?) {
        when (errorCode) {
            ERROR_RATE_LIMITED -> _status.update { it.copy(pausedUntilMs = now() + RATE_LIMIT_PAUSE_MS) }
            ERROR_INVALID_KEY, ERROR_SUSPENDED_KEY -> _status.update { it.copy(rejectedKeys = it.rejectedKeys + (apiKey to errorCode)) }
        }
    }

    companion object {
        /** GUESS: 4 a second at most, under the cited 5 a second, so a fallback burst stays below it. */
        const val MIN_GAP_MS = 250L

        /** GUESS: long enough for a 5-minute average to recover. */
        const val RATE_LIMIT_PAUSE_MS = 10 * 60_000L

        const val ERROR_INVALID_KEY = 10
        const val ERROR_SUSPENDED_KEY = 26
        const val ERROR_RATE_LIMITED = 29
    }
}
