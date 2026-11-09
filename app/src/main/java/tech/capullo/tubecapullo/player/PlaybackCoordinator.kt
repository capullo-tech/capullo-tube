package tech.capullo.tubecapullo.player

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.capullo.tubecapullo.data.youtube.YtVideo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The UI ↔ PlaybackService bridge. The service owns the ExoPlayer and the StationLoop (the FIFO
 * tee chain and the loop's player seam are built in the service), so the UI talks through this
 * singleton: station seeds and transport commands flow in, playback state and user-facing events
 * flow out.
 */
@Singleton
class PlaybackCoordinator @Inject constructor() {

    data class State(
        val video: YtVideo? = null,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
    )

    enum class Command { TOGGLE_PLAY_PAUSE, NEXT, STOP }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    // replay = 1: the first seed races the service's cold start - the service subscribes after
    // onCreate builds the player, and replay hands it the seed that started it.
    private val _seeds = MutableSharedFlow<YtVideo>(replay = 1)
    val seeds = _seeds.asSharedFlow()

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 4)
    val commands = _commands.asSharedFlow()

    /** User-facing one-liners from the service ("skipped an unplayable track"), shown as toasts. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /** Start (or restart) a station from [seed]. */
    fun startStation(seed: YtVideo) {
        _seeds.tryEmit(seed)
    }

    fun togglePlayPause() {
        _commands.tryEmit(Command.TOGGLE_PLAY_PAUSE)
    }

    fun next() {
        _commands.tryEmit(Command.NEXT)
    }

    fun stop() {
        _commands.tryEmit(Command.STOP)
    }

    /** Service-side: publish the player's state to the UI. */
    fun updateState(transform: (State) -> State) {
        _state.value = transform(_state.value)
    }

    /** Service-side: surface a user-facing message. */
    fun postEvent(message: String) {
        _events.tryEmit(message)
    }
}
