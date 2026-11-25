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
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.player.FifoAudioBufferSink
import tech.capullo.audio.player.FifoFormat
import tech.capullo.audio.snapcast.SnapcastArt
import tech.capullo.audio.snapcast.SnapcastArtwork
import tech.capullo.audio.snapcast.SnapcastRemoteArt
import tech.capullo.audio.tunnel.TunnelManager
import tech.capullo.tubecapullo.MainActivity
import tech.capullo.tubecapullo.data.lyrics.LyricsRepository
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

    @Inject lateinit var lyrics: LyricsRepository

    @Inject lateinit var tunnelManager: TunnelManager

    private var loop: StationLoop? = null

    private var retriedVideoId: String? = null

    private val skipMutex = Mutex()

    private var stallProgress: Pair<Long, Long>? = null
    private var stallSinceMs = 0L

    // Mirrors ExoPlayer's internal rebuffering flag, which picks the buffer it waits for: true only
    // once playback fell from READY to BUFFERING by itself while meant to play. A seek, a new item
    // or a play/pause change clears it, as ExoPlayerImplInternal does.
    private var rebuffering = false
    private var lastPlaybackState = Player.STATE_IDLE
    private var bufferSampleJob: Job? = null

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

    // Art for web players and listeners, as in QuantumCast: the best thumbnail is downloaded and
    // published through SnapcastArtwork, so our snapserver serves the full image (`artUrl` = its path,
    // same-origin for the web player, so through the public link too) and only a tiny thumbnail rides
    // in `artData`. Until it lands, or when nothing downloads, `artUrl` is the YouTube URL itself so web
    // players and listeners can load it directly. Touched on the main thread only.
    private var snapArtSource: BroadcastArtSource? = null

    private var snapArt: SnapcastArt? = null

    // Source already tried, whatever came of it: one attempt per track, so a dead network is not
    // hit again at every rebuffer. The direct `artUrl` above covers a track whose download failed.
    private var snapArtAttempted: BroadcastArtSource? = null
    private var snapArtJob: Job? = null
    private val snapArtwork by lazy { SnapcastArtwork.get(applicationContext) }

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
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        *FifoFormat.stage(),
                        TeeAudioProcessor(sink),
                    ),
                )
                .build()
        }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)

        val exo = ExoPlayer.Builder(this, renderersFactory)
            // The builder's own default, named because bufferThresholdMs reads the buffering ring's
            // thresholds from DefaultLoadControl's constants: another LoadControl needs them there.
            .setLoadControl(DefaultLoadControl())
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
                val knownMs = exo.duration.takeIf { d -> d != C.TIME_UNSET }
                coordinator.updateState {
                    it.copy(
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                        bufferProgress = null,
                        durationMs = knownMs ?: it.durationMs,
                    )
                }
                if (playbackState == Player.STATE_READY && knownMs != null) learnDuration(knownMs)
                if (playbackState == Player.STATE_ENDED) {
                    serviceScope.launch { loop?.advance() }
                }
            }

            // After the individual callbacks, so a batch is seen whole: a seek reports its
            // discontinuity and its fall to BUFFERING together, and must not read as a rebuffer.
            override fun onEvents(player: Player, events: Player.Events) {
                val state = exo.playbackState
                val clearsRebuffering = events.containsAny(
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
                )
                if (clearsRebuffering) {
                    rebuffering = false
                } else if (state == Player.STATE_BUFFERING && lastPlaybackState == Player.STATE_READY) {
                    rebuffering = exo.playWhenReady &&
                        exo.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
                }
                if (state != Player.STATE_BUFFERING) rebuffering = false
                lastPlaybackState = state
                // A new item starts its own measure, never one carried over from the last.
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) stopBufferSampling()
                if (state == Player.STATE_BUFFERING && exo.currentMediaItem != null) {
                    startBufferSampling(exo)
                } else {
                    stopBufferSampling()
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
            video?.let(lyrics::onTrackStarted)
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
            coordinator.updateState { it.copy(isBuffering = false, bufferProgress = null) }
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
                    is PlaybackCoordinator.StartRequest.Source ->
                        stationEngine.start(request.source, request.preloaded)
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
                    PlaybackCoordinator.Command.PAUSE -> exo.playWhenReady = false
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
        // The public link lives while the user asked for it (the QR dialog's Cloudflare chip) and
        // the broadcast is up. The triple drops the group churn of snapcast state, which would
        // otherwise re-run start() per update.
        serviceScope.launch {
            combine(tunnelManager.requested, snapcastManager.state) { requested, snap ->
                Triple(requested, snap.isBroadcasting, snap.broadcastHttpPort)
            }.distinctUntilChanged().collect { (requested, broadcasting, httpPort) ->
                if (requested && broadcasting) tunnelManager.start(httpPort) else tunnelManager.stop()
            }
        }
        // Listening to another station ends this phone's own link, request included: the broadcast
        // stack stays up through a join, so without this the link would keep serving, with its
        // Turn off out of reach behind the Listening sheet.
        serviceScope.launch {
            snapcastManager.state.map { it.listeningTo != null }.distinctUntilChanged().collect { listening ->
                if (listening) tunnelManager.setRequested(false)
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
        // Idle with nothing failed while meant to play is a stall too: on the OPPO a song once
        // went idle right after its track change and stayed silent with no error to react to.
        val idleSilently = exo.playbackState == Player.STATE_IDLE && exo.playerError == null &&
            exo.mediaItemCount > 0 && coordinator.state.value.video != null
        val wantsToPlay = exo.playWhenReady &&
            (exo.playbackState == Player.STATE_BUFFERING || exo.playbackState == Player.STATE_READY || idleSilently)
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

    // Runs on serviceScope (the main thread, the player's application thread) only while ExoPlayer
    // is buffering; the first sample is taken at once so the ring never starts on a stale figure.
    private fun startBufferSampling(exo: ExoPlayer) {
        if (bufferSampleJob?.isActive == true) return
        bufferSampleJob = serviceScope.launch(start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                val position = exo.currentPosition
                val duration = exo.duration
                val fill = bufferFill(
                    bufferedMs = exo.bufferedPosition,
                    positionMs = position,
                    thresholdMs = bufferThresholdMs(exo),
                    remainingMs = if (duration == C.TIME_UNSET) C.TIME_UNSET else duration - position,
                )
                // Nothing buffered yet (still connecting) is no measure: an empty ring would look
                // like nothing is happening, so the spinner stays until the first media arrives.
                coordinator.updateState { it.copy(bufferProgress = fill.takeIf { f -> it.isBuffering && f > 0f }) }
                delay(BUFFER_SAMPLE_MS)
            }
        }
    }

    private fun stopBufferSampling() {
        bufferSampleJob?.cancel()
        bufferSampleJob = null
    }

    // What DefaultLoadControl.shouldStartPlayback waits for: the after-rebuffer figure while
    // rebuffering, the start figure otherwise (start, seek, new item), each with its own value for
    // local URIs, which it tells apart the same way.
    private fun bufferThresholdMs(exo: ExoPlayer): Long {
        val config = exo.currentMediaItem?.localConfiguration
        val scheme = config?.uri?.scheme
        val local = config != null && (scheme.isNullOrEmpty() || scheme in DefaultLoadControl.LOCAL_PLAYBACK_SCHEMES)
        return when {
            rebuffering && local -> DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS
            rebuffering -> DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            local -> DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS
            else -> DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
        }.toLong()
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
        val videoMs = (coordinator.state.value.video?.durationSeconds ?: 0) * 1000
        return videoMs.takeIf { it > 0 } ?: coordinator.state.value.durationMs
    }

    /**
     * Gives the playing song the length the player found when it was queued without one (a
     * QuantumCast song): the engine's copies, this service's own, the lyrics lookup and Snapcast.
     */
    private fun learnDuration(durationMs: Long) {
        val video = coordinator.state.value.video ?: return
        val seconds = durationMs / 1000
        if (video.durationSeconds != 0L || seconds <= 0) return
        stationEngine.learnDuration(video.videoId, seconds)
        val learned = video.copy(durationSeconds = seconds)
        coordinator.updateState { if (it.video == video) it.copy(video = learned) else it }
        lyrics.onDurationLearned(learned)
        publishNowPlaying()
    }

    private fun buildSnapNowPlaying(): NowPlaying {
        val video = coordinator.state.value.video
        return NowPlaying(
            title = video?.title ?: "",
            artist = video?.uploader ?: "",
            album = "CapulloTube",
            artworkBase64 = snapArt?.thumbnailBase64,
            durationMs = (video?.durationSeconds ?: 0).takeIf { it > 0 }?.let { it * 1000 }
                ?: coordinator.state.value.durationMs,
            isPlaying = playerIsPlaying,
            extras = (snapArt?.urlPath ?: snapArtSource?.directUrl)?.let { mapOf("artUrl" to it) }.orEmpty(),
        )
    }

    private fun publishNowPlaying() {
        refreshSnapArt()
        val next = buildSnapNowPlaying()
        if (next == snapNowPlaying.value) return
        snapNowPlaying.value = next
        snapcastManager.notifyPropertiesChanged()
    }

    // Download the playing video's art and publish it; re-publish the now-playing when it lands.
    private fun refreshSnapArt() {
        val source = coordinator.state.value.video?.let(BroadcastArtSource::of)
        // Same art: published, in flight, or already tried.
        if (source == snapArtSource &&
            (source == null || snapArt != null || source == snapArtAttempted || snapArtJob?.isActive == true)
        ) {
            return
        }
        snapArtJob?.cancel()
        snapArtSource = source
        snapArt = null
        if (source == null) return
        snapArtJob = serviceScope.launch {
            for (url in source.downloadUrls) {
                // Capped, with timeouts, cancelled with this job. Null on any failure: a video
                // without maxresdefault answers 404.
                val bytes = SnapcastRemoteArt.download(url) ?: continue
                // Named and labelled from the bytes, never from the URL's extension.
                val art = snapArtwork.publish(bytes)
                if (snapArtSource != source) return@launch // track changed while downloading
                snapArtAttempted = source
                Log.i(STATION_TAG, "art url=$url path=${art?.urlPath ?: "-"}")
                snapArt = art ?: return@launch
                snapNowPlaying.value = buildSnapNowPlaying()
                snapcastManager.notifyPropertiesChanged()
                return@launch
            }
            if (snapArtSource == source) {
                snapArtAttempted = source
                Log.i(STATION_TAG, "art url=${source.directUrl} result=not-downloaded")
            }
        }
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

    /**
     * Swiping the app out of recents ends everything: media3's default keeps a playing service
     * alive, and with it the broadcast and a public link nobody could turn off any more. [onDestroy]
     * does the teardown (broadcast, link and its request, player).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stationEngine.persistNow()
        stationEngine.onStationStarted = null
        serviceScope.cancel()
        // serviceScope is gone, so the collector above can't see the broadcast end; without this
        // the singleton would keep cloudflared up against a dead snapserver port. The request goes
        // with it: the next session opens no public link until asked again.
        tunnelManager.setRequested(false)
        tunnelManager.stop()
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
