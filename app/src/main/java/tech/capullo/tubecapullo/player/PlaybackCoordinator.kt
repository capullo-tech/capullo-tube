package tech.capullo.tubecapullo.player

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
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
    )

    enum class Command { TOGGLE_PLAY_PAUSE, NEXT, PREVIOUS, STOP }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    sealed interface StartRequest {
        data class Source(val source: PlaySource) : StartRequest

        data class Resume(val stationId: Long) : StartRequest
    }

    private val _starts = MutableSharedFlow<StartRequest>(replay = 1)
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

    fun start(source: PlaySource) = request(StartRequest.Source(source))

    fun resume(stationId: Long) = request(StartRequest.Resume(stationId))

    private fun request(start: StartRequest) {
        context.startService(Intent(context, PlaybackService::class.java))
        _starts.tryEmit(start)
    }

    fun playNow(video: YtVideo, again: Boolean = false) {
        _jumps.tryEmit(Jump(video, again))
    }

    fun togglePlayPause() {
        _commands.tryEmit(Command.TOGGLE_PLAY_PAUSE)
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
