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

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var snapcastManager: SnapcastManager

    @Inject lateinit var coordinator: PlaybackCoordinator

    @Inject lateinit var stationEngine: StationEngine

    @Inject lateinit var extractor: YouTubeExtractorClient

    private var loop: StationLoop? = null

    private var retriedVideoId: String? = null

    private val skipMutex = Mutex()

    private var stallProgress: Pair<Long, Long>? = null
    private var stallSinceMs = 0L

    @Volatile private var playerSnapshot = "not started"

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var fifoSink: FifoAudioBufferSink? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile private var playerIsPlaying = false

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

        val fifoPath = snapcastManager.prepareBroadcast()
        val sink = FifoAudioBufferSink(fifoPath, enableKeepAlive = false).also {
            fifoSink = it
            it.open()
        }

        val renderersFactory = object : DefaultRenderersFactory(this) {
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
                val resampler = SonicAudioProcessor().apply { setOutputSampleRateHz(48000) }
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
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
                false,
            )
            .setHandleAudioBecomingNoisy(false)
            .build()
        player = exo
        exo.volume = 0f

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playerIsPlaying = isPlaying
                if (isPlaying) {
                    fifoSink?.enableWrites()
                    snapcastManager.startBroadcast(snapNowPlaying, snapController)
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

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivity)
            .build()
            .also { addSession(it) }

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
            val startAt = stationEngine.startPositionFor(index)
            coordinator.updatePosition(startAt ?: 0)
            exo.currentMediaItem?.let { item ->
                exo.replaceMediaItem(
                    exo.currentMediaItemIndex,
                    item.buildUpon()
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(video?.let { TitleCleaner.clean(it.title, it.uploader) })
                                .setArtist(video?.uploader)
                                .setArtworkUri(video?.thumbnailUrl?.let(Uri::parse))
                                .build(),
                        )
                        .build(),
                )
            }
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
            Log.i(STATION_TAG, "loop waiting at=${stationLoop.waitingAt}")
            coordinator.updateState { it.copy(isBuffering = false) }
            coordinator.postEvent("Looking for the next track…")
        }
        serviceScope.launch {
            stationEngine.state.collect {
                if (stationLoop.waitingAt != null && stationLoop.resumeIfWaiting()) {
                    Log.i(STATION_TAG, "loop resumed index=${stationLoop.currentIndex}")
                }
            }
        }

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
        serviceScope.launch {
            while (true) {
                val position = exo.currentPosition
                coordinator.updatePosition(position)
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
        stallSinceMs = SystemClock.elapsedRealtime()
        recoverStream("stall")
    }

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

    override fun dump(fd: FileDescriptor?, writer: PrintWriter?, args: Array<out String>?) {
        if (writer == null) return
        stationEngine.dump(writer)
        writer.println("player: $playerSnapshot")
        writer.flush()
    }

    override fun onDestroy() {
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
        const val EXTRA_OPEN_PLAYER = "tech.capullo.tubecapullo.OPEN_PLAYER"

        private const val STATION_TAG = "TubeStation"

        private const val POSITION_TICK_MS = 500L

        private val FORCED_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )

        private const val STALL_TIMEOUT_MS = 15_000L

        private fun stateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }

        private const val STREAM_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:129.0) Gecko/20100101 Firefox/129.0"
    }
}
