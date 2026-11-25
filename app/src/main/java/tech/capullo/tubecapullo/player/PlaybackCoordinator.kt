package tech.capullo.tubecapullo.player

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stationEngine: StationEngine,
) {
    data class State(
        val video: YtVideo? = null,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val durationMs: Long = 0,
        /**
         * While [isBuffering], how far ExoPlayer's buffer has filled toward what it needs to play,
         * 0 to 1. Null when there is no measure, such as while the stream URL is still being
         * resolved, and always when not buffering.
         */
        val bufferProgress: Float? = null,
    )

    enum class Command { TOGGLE_PLAY_PAUSE, PAUSE, NEXT, PREVIOUS, STOP }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    sealed interface StartRequest {
        /** [preloaded]: see StationEngine.start. */
        data class Source(val source: PlaySource, val preloaded: List<YtVideo>? = null) : StartRequest

        data class Resume(val stationId: Long) : StartRequest
    }

    // Only the newest start matters: a newer request replaces one the service has not collected
    // yet, so emitting never fails and every request either reaches the engine or is replaced.
    private val _starts = MutableSharedFlow<StartRequest>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val starts = _starts.asSharedFlow()

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 4)
    val commands = _commands.asSharedFlow()

    private val _position = MutableStateFlow(0L)
    val position = _position.asStateFlow()

    private val _seeks = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val seeks = _seeks.asSharedFlow()

    data class Jump(val video: YtVideo, val again: Boolean)

    private val _jumps = MutableSharedFlow<Jump>(extraBufferCapacity = 4)
    val jumps = _jumps.asSharedFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    fun start(source: PlaySource, preloaded: List<YtVideo>? = null) = request(StartRequest.Source(source, preloaded))

    fun resume(stationId: Long) = request(StartRequest.Resume(stationId))

    private fun request(start: StartRequest) {
        // Synchronously, before the service exists to receive the request: the Player opened
        // right after this waits for the start instead of closing on an empty station.
        stationEngine.expectStart()
        try {
            context.startService(Intent(context, PlaybackService::class.java))
        } catch (e: IllegalStateException) {
            // Android refuses a service start from the background; nothing would play it.
            stationEngine.dropExpectedStart("service-refused error=\"${e.message}\"")
            return
        } catch (e: SecurityException) {
            stationEngine.dropExpectedStart("service-refused error=\"${e.message}\"")
            return
        }
        _starts.tryEmit(start)
    }

    fun playNow(video: YtVideo, again: Boolean = false) {
        _jumps.tryEmit(Jump(video, again))
    }

    fun togglePlayPause() {
        _commands.tryEmit(Command.TOGGLE_PLAY_PAUSE)
    }

    fun pause() {
        _commands.tryEmit(Command.PAUSE)
    }

    fun next() {
        _commands.tryEmit(Command.NEXT)
    }

    fun previous() {
        _commands.tryEmit(Command.PREVIOUS)
    }

    fun setShuffle(on: Boolean) {
        stationEngine.setShuffle(on)
    }

    fun cycleRepeat() {
        stationEngine.cycleRepeat()
    }

    fun seekTo(positionMs: Long) {
        _seeks.tryEmit(positionMs)
    }

    fun updatePosition(positionMs: Long) {
        _position.value = positionMs
    }

    fun stop() {
        _commands.tryEmit(Command.STOP)
    }

    fun updateState(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }

    fun postEvent(message: String) {
        _events.tryEmit(message)
    }
}
