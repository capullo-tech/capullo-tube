package tech.capullo.tubecapullo.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.player.FifoAudioBufferSink
import tech.capullo.tubecapullo.MainActivity
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.snapcast.SnapcastManager
import tech.capullo.tubecapullo.station.RepeatMode
import tech.capullo.tubecapullo.station.StationEngine
import tech.capullo.tubecapullo.station.StationLoop
import tech.capullo.tubecapullo.util.TitleCleaner
import java.io.FileDescriptor
import java.io.PrintWriter
import javax.inject.Inject

/**
 * Playback + Snapcast broadcast service (shell modeled on telecloud's PlaybackService; since M4 it
 * also hosts the StationLoop - the service owns the player, so it owns the loop's player seam).
 *
 * Audio path: ExoPlayer decodes the stream; the sink chain forces the PCM to 48000:16:2
 * ([ChannelMixing → Sonic]), then a TeeAudioProcessor copies it into the snapserver FIFO. The
 * AudioTrack output stays (it paces playback and provides the position clock) but player volume is
 * 0 - the audible output on this device is the local snapclient, which is perfectly in sync with
 * every other Snapcast/web listener on the LAN.
 *
 * Not ported from telecloud (no caller / no need yet): the balance processor (no calibration UI),
 * the silence watchdog (guards corrupt local files; a YouTube stream that fails surfaces as a
 * player error, which the ViewModel's retry already handles), and the listen-in player swap.
 */
// androidx.annotation.OptIn, not kotlin.OptIn: lint's UnsafeOptInUsageError only honors the
// androidx form for media3's @UnstableApi; kotlin.OptIn compiles but leaves lint errors.
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var snapcastManager: SnapcastManager

    @Inject lateinit var coordinator: PlaybackCoordinator

    @Inject lateinit var stationEngine: StationEngine

    @Inject lateinit var extractor: YouTubeExtractorClient

    private var loop: StationLoop? = null

    /** One free re-resolve per track on a player error (expired stream URL); a second error on the
     *  same track means it's genuinely unplayable → skip. */
    private var retriedVideoId: String? = null

    /** Serializes user skips and Previous: each Next or Previous is its own step, in order,
     *  without blocking commands. */
    private val skipMutex = Mutex()

    /** Stall watchdog state: last (position, buffered) seen and since when it has not moved. */
    private var stallProgress: Pair<Long, Long>? = null
    private var stallSinceMs = 0L

    /** ExoPlayer's state as of the last tick, for dump() (which runs off the Main thread). */
    @Volatile private var playerSnapshot = "not started"

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var fifoSink: FifoAudioBufferSink? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Mirror for the snapcontrol plugin, which queries from an IO thread while ExoPlayer only
    // allows access from its application thread.
    @Volatile private var playerIsPlaying = false

    // The engine's SnapcontrolPlugin is contract-driven: a StateFlow<NowPlaying> (read) + a
    // PlaybackController (transport). MutableStateFlow is-a StateFlow, so it satisfies the
    // plugin's read-only param directly.
    private val snapNowPlaying = MutableStateFlow(NowPlaying.EMPTY)

    private val snapController = object : PlaybackController {
        override fun play() = runOnMain { player?.play() }
        override fun pause() = runOnMain { player?.pause() }

        override fun next() = runOnMain { serviceScope.launch { skipCurrent() } }

        override fun previous() = runOnMain { serviceScope.launch { skipMutex.withLock { goBack() } } }
        override fun seekTo(positionMs: Long) {}
    }

    override fun onCreate() {
        super.onCreate()

        // FIFO + snapserver process wrapper must exist before the sink opens the pipe.
        val fifoPath = snapcastManager.prepareBroadcast()
        // enableKeepAlive = false: this sink is fed in bursts (mixer/resampler processors paced by
        // AudioTrack backpressure), so the silence keep-alive would trip during normal drain waits
        // and overfeed the FIFO → stutter. Same call telecloud made for the same sink shape.
        val sink = FifoAudioBufferSink(fifoPath, enableKeepAlive = false).also {
            fifoSink = it
            it.open()
        }

        // Sink chain: [mix → 2ch] → [resample → 48000] → [tee → FIFO].
        val renderersFactory = object : DefaultRenderersFactory(this) {
            // Prefer platform decoders, with the bundled Media3 FFmpeg extension (on the classpath
            // via capullo-audio) as fallback for formats the platform can't handle.
            init {
                setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
            }

            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val mixer = ChannelMixingAudioProcessor().apply {
                    putChannelMixingMatrix(ChannelMixingMatrix.create(1, 2))
                    putChannelMixingMatrix(ChannelMixingMatrix.create(2, 2))
                }
                // LOCKSTEP with SnapserverProcess.SAMPLE_FORMAT (48000): the FIFO the tee writes is
                // read by snapserver at that rate, so this resampler must output the same. 48000
                // lets Opus (always 48k) pass through unresampled; 44.1k AAC upsamples once.
                val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(48000) }
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // keep the chain in 16-bit PCM
                    .setAudioProcessorChain(
                        DefaultAudioSink.DefaultAudioProcessorChain(
                            mixer,
                            resampler,
                            TeeAudioProcessor(sink),
                        ),
                    )
                    .build()
            }
        }

        // googlevideo serves a different (or no) body without a browser-shaped user agent, and
        // Media3 sets headers per-DataSource.Factory rather than per-MediaItem - so it has to be
        // established here, once, for every item the player opens. Same UA the extractor sends:
        // the stream URL was handed out to it, and googlevideo is picky about being asked for it
        // by something else.
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpDataSourceFactory)),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                // handleAudioFocus = FALSE: ExoPlayer here is a silent (volume 0) clock that feeds
                // the FIFO - it must never react to focus. The audible endpoint is the local
                // snapclient, which owns focus; a focus-handling ExoPlayer would see the
                // snapclient's own grab as a "loss" and pause itself, stalling the tee.
                /* handleAudioFocus = */
                false,
            )
            // Becoming-noisy (headphone unplug) must not pause the broadcast either - other
            // rooms/web players keep listening.
            .setHandleAudioBecomingNoisy(false)
            .build()
        player = exo
        // Local audio comes from the snapclient; the tee sits pre-volume so the FIFO always
        // receives full-scale PCM.
        exo.volume = 0f

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerIsPlaying = isPlaying
                if (isPlaying) {
                    // Only NOW start feeding the FIFO: writing during preroll deadlocks - the 64KB
                    // pipe has no reader yet and a blocked tee stalls READY forever.
                    fifoSink?.enableWrites()
                    snapcastManager.startBroadcast(snapNowPlaying, snapController)
                    // Explicit play (also an app-switch back to us): evict a co-broadcasting app's
                    // local snapclient from the speaker. No-op after the focus is already ours.
                    snapcastManager.reclaimAudioFocus()
                }
                coordinator.updateState { it.copy(isPlaying = isPlaying) }
                publishNowPlaying()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.i(
                    STATION_TAG,
                    "player state=${stateName(playbackState)} " +
                        "id=${coordinator.state.value.video?.videoId ?: "-"} " +
                        "playWhenReady=${exo.playWhenReady}",
                )
                coordinator.updateState {
                    it.copy(
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                        durationMs = exo.duration.takeIf { d -> d != C.TIME_UNSET } ?: it.durationMs,
                    )
                }
                if (playbackState == Player.STATE_ENDED) {
                    // The station queue is finite-and-growing (isRotating = false), so track end
                    // means advance - mirroring the engine loop's auto-advance semantics. Natural
                    // end is NOT a skip: no `skip` log line.
                    serviceScope.launch { loop?.advance() }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(
                    STATION_TAG,
                    "player error id=${coordinator.state.value.video?.videoId ?: "-"} " +
                        "code=${error.errorCodeName} msg=\"${error.message}\"",
                )
                recoverStream("error")
            }
        })
        // The station queue lives in StationEngine (one MediaItem at a time), so ExoPlayer never
        // has a next or previous item and would disable those commands - breaking Bluetooth and
        // lock-screen skip and back. Force them available and route them through the station
        // (telecloud's fix for Next; Previous the same way, owner decision 2026-11-12).
        val forwardingPlayer = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands()
                .buildUpon()
                .addAll(*FORCED_COMMANDS)
                .build()

            override fun isCommandAvailable(command: Int): Boolean = command in FORCED_COMMANDS || super.isCommandAvailable(command)

            override fun seekToNext() {
                serviceScope.launch { skipMutex.withLock { skipCurrent() } }
            }

            override fun seekToNextMediaItem() {
                serviceScope.launch { skipMutex.withLock { skipCurrent() } }
            }

            override fun seekToPrevious() {
                serviceScope.launch { skipMutex.withLock { goBack() } }
            }

            override fun seekToPreviousMediaItem() {
                serviceScope.launch { skipMutex.withLock { goBack() } }
            }
        }

        // Notification / lock-screen tap opens the Player. FLAG_UPDATE_CURRENT: extras are not
        // part of a PendingIntent's identity, so an older one without the extra would be reused.
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivity)
            .build()
            // addSession EXPLICITLY: MediaSessionService only learns about a session when a
            // MediaController connects (onGetSession) or when it's added by hand. Nothing in this
            // app connects a controller - the UI talks through PlaybackCoordinator - so without
            // this line the service's notification manager never attaches, startForeground is
            // never called, and Samsung kills the backgrounded process minutes into playback
            // (measured 2026-08-18: startForegroundCount=0 while audibly playing; the mid-track
            // deaths the owner reported).
            .also { addSession(it) }

        // The station loop: its player seam is this ExoPlayer. Loop calls arrive on serviceScope
        // (Main), which is ExoPlayer's application thread.
        val stationLoop = StationLoop(
            stationEngine,
            playback = { request ->
                exo.setMediaItem(
                    MediaItem.Builder()
                        .setUri(request.uri)
                        .setMimeType(request.mimeType)
                        .build(),
                )
                exo.prepare()
                exo.playWhenReady = true
            },
        ).also { loop = it }
        stationLoop.onTrackChanged = { index ->
            val video = stationEngine.videoAt(index)
            coordinator.updateState { it.copy(video = video, isBuffering = true, durationMs = 0) }
            // A Continue resume starts its track where it was left; every other start plays from 0.
            val startAt = stationEngine.startPositionFor(index)
            coordinator.updatePosition(startAt ?: 0)
            // Update the session metadata too, so the notification / lock screen track the station.
            exo.currentMediaItem?.let { item ->
                exo.replaceMediaItem(
                    exo.currentMediaItemIndex,
                    item.buildUpon()
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                // Display only: the raw title stays the record everywhere else.
                                .setTitle(video?.let { TitleCleaner.clean(it.title, it.uploader) })
                                .setArtist(video?.uploader)
                                .setArtworkUri(video?.thumbnailUrl?.let(Uri::parse))
                                .build(),
                        )
                        .build(),
                )
            }
            // After the metadata swap, so the seek lands on the item that plays.
            startAt?.let { exo.seekTo(it) }
            publishNowPlaying()
        }
        stationLoop.onResolveFailed = { index, id, error ->
            val title = stationEngine.videoAt(index)?.title
            Log.w(
                STATION_TAG,
                "resolve id=$id index=$index result=skipped error=\"${error.message}\"",
            )
            coordinator.postEvent("Can't play ${title ?: id}: ${error.message ?: "unknown error"}")
        }
        stationLoop.onQueueExhausted = {
            // Not "ran dry" yet: a Mix chain may still be landing. Stop the loading spinner, say
            // what is happening, and let the state collector below resume the loop.
            Log.i(STATION_TAG, "loop waiting at=${stationLoop.waitingAt}")
            coordinator.updateState { it.copy(isBuffering = false) }
            coordinator.postEvent("Looking for the next track…")
        }
        // Resume a waiting loop as soon as the engine grows the queue (a chain, a pin, a like).
        serviceScope.launch {
            stationEngine.state.collect {
                if (stationLoop.waitingAt != null && stationLoop.resumeIfWaiting()) {
                    Log.i(STATION_TAG, "loop resumed index=${stationLoop.currentIndex}")
                }
            }
        }

        // UI → service: play sources (replay=1 covers the cold-start race) and transport commands.
        // The engine calls back once a source has actually replaced the station - Liked with no
        // likes stays idle, and a playlist or channel switches only once its list has landed - so
        // the loop restarts exactly then. The callback may arrive on IO: hop to Main.
        stationEngine.onStationStarted = {
            serviceScope.launch {
                retriedVideoId = null
                coordinator.updateState {
                    PlaybackCoordinator.State(video = stationEngine.videoAt(0), isBuffering = true)
                }
                stationLoop.start()
            }
        }
        serviceScope.launch {
            coordinator.starts.collect { request ->
                when (request) {
                    is PlaybackCoordinator.StartRequest.Source -> stationEngine.start(request.source)
                    is PlaybackCoordinator.StartRequest.Resume -> stationEngine.resume(request.stationId)
                }
            }
        }
        // Up next taps: the engine moves the track up, the loop advances to it. Under the skip
        // mutex, so a jump right after a Next lands after it and reads the station that Next
        // left.
        serviceScope.launch {
            coordinator.jumps.collect { jump ->
                serviceScope.launch {
                    skipMutex.withLock {
                        val index = stationEngine.jumpTo(jump.video, jump.again) ?: return@withLock
                        retriedVideoId = null
                        loop?.advanceTo(index)
                    }
                }
            }
        }
        // Repeat one: ExoPlayer loops the item itself, so it never reaches STATE_ENDED and the
        // loop never advances on its own. Next still moves on: every user next goes through
        // skipCurrent -> loop.advance, which replaces the item rather than asking ExoPlayer for
        // its next one, and the mode stays on to loop the new track.
        serviceScope.launch {
            stationEngine.state
                .map { it.repeat == RepeatMode.ONE }
                .distinctUntilChanged()
                .collect { one ->
                    exo.repeatMode = if (one) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                }
        }
        launchPlayerTickers(exo)
        serviceScope.launch {
            coordinator.commands.collect { cmd ->
                when (cmd) {
                    PlaybackCoordinator.Command.TOGGLE_PLAY_PAUSE -> {
                        if (exo.playbackState == Player.STATE_ENDED) {
                            exo.seekTo(0)
                            exo.playWhenReady = true
                        } else {
                            exo.playWhenReady = !exo.playWhenReady
                        }
                    }
                    // Launched, not awaited: a slow resolve must not hold up play/pause behind
                    // it. The mutex keeps two quick Nexts as two skips, in order.
                    PlaybackCoordinator.Command.NEXT ->
                        serviceScope.launch { skipMutex.withLock { skipCurrent() } }
                    PlaybackCoordinator.Command.PREVIOUS ->
                        serviceScope.launch { skipMutex.withLock { goBack() } }
                    PlaybackCoordinator.Command.STOP -> {
                        exo.stop()
                        coordinator.updateState { PlaybackCoordinator.State() }
                    }
                }
            }
        }
    }

    private fun launchPlayerTickers(exo: ExoPlayer) {
        serviceScope.launch { coordinator.seeks.collect { exo.seekTo(it) } }
        // ponytail: fixed 500 ms poll, same cadence telecloud's seek bar uses; ticks while paused too
        // (cheap: one property read), switch to an isPlaying-gated loop if battery ever shows it.
        serviceScope.launch {
            while (true) {
                val position = exo.currentPosition
                coordinator.updatePosition(position)
                // Item 12: the engine counts the play once the playhead passes its threshold.
                coordinator.state.value.video?.let {
                    stationEngine.onProgress(it.videoId, position, currentDurationMs())
                }
                watchForStall(exo, position)
                playerSnapshot = "state=${stateName(exo.playbackState)} " +
                    "playWhenReady=${exo.playWhenReady} isPlaying=${exo.isPlaying} " +
                    "isLoading=${exo.isLoading} position=$position " +
                    "buffered=${exo.bufferedPosition} " +
                    "error=${exo.playerError?.errorCodeName ?: "-"} " +
                    "loop=${loop?.currentIndex} waitingAt=${loop?.waitingAt ?: "-"}"
                delay(POSITION_TICK_MS)
            }
        }
    }

    /**
     * A stream that should be playing but makes no progress is treated like a player error. Seen
     * 2026-11-11: after a skip the new googlevideo load opened a socket and then nothing - no
     * error, no audio - and the station sat silent until the owner gave up. ExoPlayer's own retry
     * policy can take a minute or more to surface such a load as an error.
     */
    private fun watchForStall(exo: ExoPlayer, position: Long) {
        val wantsToPlay = exo.playWhenReady &&
            (exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_READY)
        val progress = position to exo.bufferedPosition
        if (!wantsToPlay || exo.isPlaying || progress != stallProgress) {
            stallProgress = progress
            stallSinceMs = SystemClock.elapsedRealtime()
            return
        }
        val stalledMs = SystemClock.elapsedRealtime() - stallSinceMs
        if (stalledMs < STALL_TIMEOUT_MS) return
        Log.w(
            STATION_TAG,
            "player stall id=${coordinator.state.value.video?.videoId ?: "-"} " +
                "state=${stateName(exo.playbackState)} position=$position " +
                "buffered=${exo.bufferedPosition} stalledMs=$stalledMs",
        )
        stallSinceMs = SystemClock.elapsedRealtime() // one recovery per timeout window
        recoverStream("stall")
    }

    /** A failed or stalled stream: the usual cause is a stream URL gone bad, so invalidate it and
     *  re-resolve once; a second failure on the same track is a real skip. */
    private fun recoverStream(reason: String) {
        val video = coordinator.state.value.video ?: return
        extractor.invalidate(video.videoId)
        serviceScope.launch {
            if (retriedVideoId != video.videoId) {
                retriedVideoId = video.videoId
                Log.i(STATION_TAG, "recover id=${video.videoId} reason=$reason action=re-resolve")
                loop?.replayCurrent()
            } else {
                Log.i(STATION_TAG, "recover id=${video.videoId} reason=$reason action=skip")
                coordinator.postEvent("Can't play · ${video.title}")
                loop?.advance()
            }
        }
    }

    /** The one user-initiated-next path (bar button, lock screen/BT, web player). In album mode
     *  with chapters it seeks to the next chapter ([StationEngine.nextChapter] logs it); on the
     *  last chapter, and anywhere else, a skip only advances: it carries no signal and changes no
     *  station state. Natural track end bypasses this, so the `skip` line counts only the
     *  listener's Nexts. */
    private suspend fun skipCurrent() {
        val position = player?.currentPosition ?: 0
        stationEngine.nextChapter(position)?.let { start ->
            player?.seekTo(start)
            return
        }
        val video = coordinator.state.value.video
        Log.i(
            STATION_TAG,
            "skip id=${video?.videoId ?: "-"} pos=$position dur=${currentDurationMs()}",
        )
        loop?.advance()
    }

    /** The one Previous path (Player button, cover swipe, notification, lock screen, Bluetooth,
     *  web player). In album mode with chapters the chapter decides first
     *  ([StationEngine.previousChapter]: restart it or go to the one before). Else the engine
     *  decides by the playhead ([StationEngine.previous], which logs the `previous` line):
     *  restart this track, or advance the loop to the song or the restored station's track it
     *  put at the returned index. Not a skip. */
    private suspend fun goBack() {
        val exo = player ?: return
        stationEngine.previousChapter(exo.currentPosition)?.let { start ->
            exo.seekTo(start)
            return
        }
        val index = stationEngine.previous(exo.currentPosition)
        if (index == null) {
            exo.seekTo(0)
            return
        }
        retriedVideoId = null
        loop?.advanceTo(index)
    }

    /** The player's track length, or the video's own while the player doesn't know it yet. */
    private fun currentDurationMs(): Long {
        val playerDuration = player?.duration ?: C.TIME_UNSET
        if (playerDuration != C.TIME_UNSET && playerDuration > 0) return playerDuration
        return (coordinator.state.value.video?.durationSeconds ?: 0) * 1000
    }

    private fun buildSnapNowPlaying(): NowPlaying {
        val video = coordinator.state.value.video
        return NowPlaying(
            title = video?.title ?: "",
            artist = video?.uploader ?: "",
            album = "CapulloTube",
            durationMs = (video?.durationSeconds ?: 0) * 1000,
            isPlaying = playerIsPlaying,
        )
    }

    // Push the current metadata to web players / snapclients. Skips the push when nothing changed:
    // re-emitting an identical state is pure noise for the control plugin.
    private fun publishNowPlaying() {
        val next = buildSnapNowPlaying()
        if (next == snapNowPlaying.value) return
        snapNowPlaying.value = next
        snapcastManager.notifyPropertiesChanged()
    }

    private fun runOnMain(block: () -> Unit) {
        serviceScope.launch { block() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    /** `adb shell dumpsys activity service tech.capullo.tubecapullo/.player.PlaybackService`
     *  prints the station's full state (PLAN.md, Debugging). */
    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        if (writer == null) return
        stationEngine.dump(writer)
        // The player half of the picture: a station can look healthy while ExoPlayer is idle,
        // buffering forever or erroring. ExoPlayer may only be read on Main, so the position
        // ticker keeps this snapshot (at most POSITION_TICK_MS old).
        writer.println("player: $playerSnapshot")
        writer.flush()
    }

    override fun onDestroy() {
        // The playing station's place, for Continue: the last write may be up to 10 s old.
        stationEngine.persistNow()
        stationEngine.onStationStarted = null
        serviceScope.cancel()
        snapcastManager.stopBroadcast()
        fifoSink?.close()
        fifoSink = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        coordinator.updateState { PlaybackCoordinator.State() }
        super.onDestroy()
    }

    companion object {
        /** Boolean extra on the notification's session-activity intent: MainActivity opens the
         *  Player when it is set. */
        const val EXTRA_OPEN_PLAYER = "tech.capullo.tubecapullo.OPEN_PLAYER"

        /** Loop events share the engine's log tag so one `logcat -s TubeStation` shows the whole
         *  station. Same string as StationEngine.TAG and DebugCommands.TAG. */
        private const val STATION_TAG = "TubeStation"

        private const val POSITION_TICK_MS = 500L

        /** Next and Previous, which the one-item ExoPlayer would otherwise disable. */
        private val FORCED_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        /** No playhead and no buffer progress for this long while meant to play = stalled.
         *  GUESS: 15 s - above a slow but healthy start (googlevideo first bytes are usually
         *  under 2 s on the rig's Wi-Fi), well under the minute+ ExoPlayer's retries can take
         *  to give up. Unmeasured. */
        private const val STALL_TIMEOUT_MS = 15_000L

        private fun stateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }

        // Matches what the YouTube extractor sends (TubeCapulloDownloader.USER_AGENT mirrors this;
        // both comments name each other because the values must move together).
        private const val STREAM_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0"
    }
}
