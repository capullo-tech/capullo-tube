package tech.capullo.tubecapullo.player

import android.app.PendingIntent
import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.player.FifoAudioBufferSink
import tech.capullo.tubecapullo.MainActivity
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.snapcast.SnapcastManager
import tech.capullo.tubecapullo.station.StationEngine
import tech.capullo.tubecapullo.station.StationLoop
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

        // The station never goes back (played ids are burned); previous restarts the track,
        // which is also what radio listeners usually mean by it.
        override fun previous() = runOnMain { player?.seekTo(0) }
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
                coordinator.updateState {
                    it.copy(isBuffering = playbackState == Player.STATE_BUFFERING)
                }
                if (playbackState == Player.STATE_ENDED) {
                    // The station queue is finite-and-growing (isRotating = false), so track end
                    // means advance - mirroring the engine loop's auto-advance semantics. Natural
                    // end is NOT a skip: no taste signal here.
                    serviceScope.launch { loop?.advance() }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val video = coordinator.state.value.video ?: return
                // The usual cause is a stream URL that expired early: invalidate + one re-resolve.
                // A second failure on the same track is a real skip.
                extractor.invalidate(video.videoId)
                serviceScope.launch {
                    if (retriedVideoId != video.videoId) {
                        retriedVideoId = video.videoId
                        loop?.replayCurrent()
                    } else {
                        coordinator.postEvent("Can't play · ${video.title}")
                        loop?.advance()
                    }
                }
            }
        })

        // The station queue lives in StationEngine (one MediaItem at a time), so ExoPlayer never
        // has a next item and would disable the command - breaking Bluetooth/lock-screen skip.
        // Force the commands available and route them through the loop (telecloud's fix).
        val forwardingPlayer = object : ForwardingPlayer(exo) {
            override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands()
                .buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()

            override fun isCommandAvailable(command: Int): Boolean = command == Player.COMMAND_SEEK_TO_NEXT ||
                command == Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM ||
                super.isCommandAvailable(command)

            override fun seekToNext() {
                serviceScope.launch { skipCurrent() }
            }

            override fun seekToNextMediaItem() {
                serviceScope.launch { skipCurrent() }
            }
        }

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
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
            coordinator.updateState { it.copy(video = video, isBuffering = true) }
            // Update the session metadata too, so the notification / lock screen track the station.
            exo.currentMediaItem?.let { item ->
                exo.replaceMediaItem(
                    exo.currentMediaItemIndex,
                    item.buildUpon()
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(video?.title)
                                .setArtist(video?.uploader)
                                .build(),
                        )
                        .build(),
                )
            }
            publishNowPlaying()
        }
        stationLoop.onQueueExhausted = {
            coordinator.postEvent("Station ran dry - pick a new seed")
        }

        // UI → service: station seeds (replay=1 covers the cold-start race) and transport commands.
        serviceScope.launch {
            coordinator.seeds.collect { seed ->
                retriedVideoId = null
                coordinator.updateState { PlaybackCoordinator.State(video = seed, isBuffering = true) }
                stationEngine.startStation(seed)
                stationLoop.start()
            }
        }
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
                    PlaybackCoordinator.Command.NEXT -> skipCurrent()
                    PlaybackCoordinator.Command.STOP -> {
                        exo.stop()
                        coordinator.updateState { PlaybackCoordinator.State() }
                    }
                }
            }
        }
    }

    /** The one user-initiated-next path (bar button, lock screen/BT, web player): note the skip
     *  for taste, then advance. Natural track end bypasses this on purpose. */
    private suspend fun skipCurrent() {
        coordinator.state.value.video?.let { stationEngine.noteSkip(it.videoId) }
        loop?.advance()
    }

    private fun buildSnapNowPlaying(): NowPlaying {
        val video = coordinator.state.value.video
        return NowPlaying(
            title = video?.title ?: "",
            artist = video?.uploader ?: "",
            album = "TubeCapullo",
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

    override fun onDestroy() {
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

    private companion object {
        // Matches what the YouTube extractor sends (TubeCapulloDownloader.USER_AGENT mirrors this;
        // both comments name each other because the values must move together).
        const val STREAM_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0"
    }
}
