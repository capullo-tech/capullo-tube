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

/**
 * The UI ↔ PlaybackService bridge. The service owns the ExoPlayer and the StationLoop (the FIFO
 * tee chain and the loop's player seam are built in the service), so the UI talks through this
 * singleton: play sources and transport commands flow in, playback state and user-facing events
 * flow out. [start] also starts the service, so no screen has to.
 */
@Singleton
class PlaybackCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stationEngine: StationEngine,
) {

    data class State(
        val video: YtVideo? = null,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        /** Current track length from the player; 0 until it's known (falls back to the video's). */
        val durationMs: Long = 0,
    )

    enum class Command { TOGGLE_PLAY_PAUSE, NEXT, PREVIOUS, STOP }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /** What the service starts: a new station from a source, or a stored one (Continue). */
    sealed interface StartRequest {
        data class Source(val source: PlaySource) : StartRequest

        data class Resume(val stationId: Long) : StartRequest
    }

    // replay = 1: the first request races the service's cold start - the service subscribes after
    // onCreate builds the player, and replay hands it the request that started it.
    private val _starts = MutableSharedFlow<StartRequest>(replay = 1)
    val starts = _starts.asSharedFlow()

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 4)
    val commands = _commands.asSharedFlow()

    // Separate from State so the 500 ms position tick only recomposes the seek bar.
    private val _position = MutableStateFlow(0L)
    val position = _position.asStateFlow()

    private val _seeks = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val seeks = _seeks.asSharedFlow()

    /** An Up next tap: play [video] now. [again] is set for an Earlier row (play it once more,
     *  then go on from where the station was). */
    data class Jump(val video: YtVideo, val again: Boolean)

    private val _jumps = MutableSharedFlow<Jump>(extraBufferCapacity = 4)
    val jumps = _jumps.asSharedFlow()

    /** User-facing one-liners from the service ("skipped an unplayable track"), shown as toasts. */
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /** Start (or restart) playback from [source]; starts [PlaybackService] if it isn't running. */
    fun start(source: PlaySource) = request(StartRequest.Source(source))

    /** Resume the stored station [stationId] (a Continue card) where it was left. */
    fun resume(stationId: Long) = request(StartRequest.Resume(stationId))

    private fun request(start: StartRequest) {
        // Plain startService, NOT startForegroundService: the FGS variant demands startForeground()
        // within 5s, but stream resolution alone can take longer and MediaSessionService only
        // promotes itself once playback starts. A plain start from a foreground app is allowed and
        // has no deadline; the replay=1 on [starts] bridges the service's cold-start race.
        context.startService(Intent(context, PlaybackService::class.java))
        _starts.tryEmit(start)
    }

    /** Play [video] now (an Up next row tap): the service has the engine move it up and advances
     *  the loop to it. Not a skip. [again] marks an Earlier row, played once more before the
     *  station goes on. */
    fun playNow(video: YtVideo, again: Boolean = false) {
        _jumps.tryEmit(Jump(video, again))
    }

    fun togglePlayPause() {
        _commands.tryEmit(Command.TOGGLE_PLAY_PAUSE)
    }

    fun next() {
        _commands.tryEmit(Command.NEXT)
    }

    /** Previous: restarts the track, plays the station's previous song again, or brings back
     *  the station left by the last station change (StationEngine.previous decides). */
    fun previous() {
        _commands.tryEmit(Command.PREVIOUS)
    }

    /** Shuffle the unplayed rest of a list or a radio's queue; off restores a list's order.
     *  Engine state, no player command: the playing track is untouched. */
    fun setShuffle(on: Boolean) {
        stationEngine.setShuffle(on)
    }

    /** Collections only: repeat OFF -> ALL -> ONE -> OFF. The service follows the engine's state
     *  to loop the track under ONE. */
    fun cycleRepeat() {
        stationEngine.cycleRepeat()
    }

    fun seekTo(positionMs: Long) {
        _seeks.tryEmit(positionMs)
    }

    /** Service-side: publish the playhead. */
    fun updatePosition(positionMs: Long) {
        _position.value = positionMs
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
