package tech.capullo.tubecapullo.ui.player

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import tech.capullo.audio.ui.SnapcastControlSheet
import tech.capullo.tubecapullo.R
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator

/**
 * Full-screen now-playing, ported from telecloud-radio's PortraitPlayer: square art (the YouTube
 * thumbnail) taking whatever space the fixed info + transport leave, slim seek bar, transport row,
 * and a secondary row of Multiroom · like · search. The Telegram-only parts (reactions, download,
 * stats, queue/library sheet, sleep timer, album-art tinting) are not ported - telecloud's
 * PlayerScreen is the reference when any of them comes back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onBack: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()
    val snapState by viewModel.snapcastState.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedIds.collectAsStateWithLifecycle()
    val albumArt by viewModel.albumArt.collectAsStateWithLifecycle()

    BackHandler(onBack = onBack)

    var showSnapSheet by remember { mutableStateOf(false) }
    // Blur behind the multiroom sheet, matching telecloud and the web player's backdrop blur.
    val snapBackdropBlur by animateDpAsState(if (showSnapSheet) 12.dp else 0.dp, label = "snapBackdropBlur")
    if (showSnapSheet) {
        SnapcastControlSheet(
            groups = snapState.groups,
            onClientVolumeChange = viewModel::setClientVolume,
            onClientLatencyChange = viewModel::setClientLatency,
            localClientId = viewModel.localClientId,
            httpPort = snapState.broadcastHttpPort,
            onDismiss = { showSnapSheet = false },
        )
    }

    Scaffold(
        modifier = Modifier.blur(snapBackdropBlur),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Station") },
            )
        },
    ) { padding ->
        val video = state.video
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (video == null) {
                Text(
                    "Nothing playing. Search for a song to start a station.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            } else {
                val clients = snapState.groups.flatMap { it.clients }
                PortraitPlayer(
                    video = video,
                    state = state,
                    albumArt = albumArt,
                    isLiked = video.videoId in likedIds,
                    viewModel = viewModel,
                    connectedOthers = clients.count { it.connected && it.id != viewModel.localClientId },
                    anyConnected = clients.any { it.connected },
                    onOpenClients = { showSnapSheet = true },
                    onSearch = onBack,
                )
            }
        }
    }
}

@Composable
private fun PortraitPlayer(
    video: YtVideo,
    state: PlaybackCoordinator.State,
    albumArt: ByteArray?,
    isLiked: Boolean,
    viewModel: PlayerViewModel,
    connectedOthers: Int,
    anyConnected: Boolean,
    onOpenClients: () -> Unit,
    onSearch: () -> Unit,
) {
    // Fit-to-space (telecloud): the art is the sole weight holder, a square capped at the width,
    // so info + transport are never squeezed at any font scale.
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AlbumArtDisplay(albumArt, Modifier.size(minOf(maxWidth, maxHeight)).artGestures(viewModel))
        }
        Spacer(Modifier.height(16.dp))
        TrackInfo(video, Modifier.padding(horizontal = 28.dp))
        Spacer(Modifier.height(16.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            // The player's duration once known, else the extractor's.
            val durationMs = state.durationMs.takeIf { it > 0 } ?: (video.durationSeconds * 1000)
            SeekBar(durationMs, viewModel.position, onSeek = viewModel::seekTo)
            ControlBar(state, viewModel)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenClients) { MultiroomIcon(connectedOthers, anyConnected) }
                IconButton(onClick = viewModel::toggleLike) {
                    Icon(
                        if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isLiked) "Unlike" else "Like - more like this",
                        tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
        }
    }
}

// Surround-sound glyph with the count of OTHER connected clients in its centre (telecloud /
// QuantumCast). Lit whenever anything is connected, so it stays lit while solo.
@Composable
private fun MultiroomIcon(connectedOthers: Int, anyConnected: Boolean) {
    val tint = if (anyConnected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    }
    if (connectedOthers > 0) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_surround_sound_nodot),
                contentDescription = "Multiroom · $connectedOthers connected",
                tint = tint,
            )
            Text(
                text = connectedOthers.coerceAtMost(99).toString(),
                color = tint,
                fontSize = 8.sp,
                lineHeight = 8.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        Icon(Icons.Default.SurroundSound, contentDescription = "Multiroom", tint = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(durationMs: Long, positionState: StateFlow<Long>, onSeek: (Long) -> Unit) {
    // Collected at the leaf so the 500 ms tick only recomposes the seek bar.
    val position by positionState.collectAsStateWithLifecycle()
    // Local drag value so the thumb doesn't fight the ticker.
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shownMs = (dragValue ?: position.coerceIn(0L, durationMs).toFloat()).toLong()
    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = shownMs.toFloat(),
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onSeek(it.toLong()) }
                dragValue = null
            },
            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
            enabled = durationMs > 0L,
            thumb = {
                Box(
                    Modifier
                        .size(width = 4.dp, height = 18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(3.dp),
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatDuration(shownMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "-" + formatDuration((durationMs - shownMs).coerceAtLeast(0)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ControlBar(state: PlaybackCoordinator.State, viewModel: PlayerViewModel) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(onClick = viewModel::previous, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Restart track", modifier = Modifier.size(40.dp))
        }
        // Play/pause stays usable while the next track resolves; loading shows as a ring around it.
        Box(contentAlignment = Alignment.Center) {
            FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(68.dp)) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                )
            }
            if (state.isBuffering) CircularProgressIndicator(Modifier.size(76.dp), strokeWidth = 2.dp)
        }
        IconButton(onClick = viewModel::next, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipNext, contentDescription = "Skip - less like this", modifier = Modifier.size(40.dp))
        }
    }
}

// Tap = play/pause · swipe left = skip · swipe right = restart (telecloud's art gestures).
private fun Modifier.artGestures(viewModel: PlayerViewModel): Modifier = this
    .pointerInput(Unit) { detectTapGestures(onTap = { viewModel.togglePlayPause() }) }
    .pointerInput(Unit) {
        val threshold = 90.dp.toPx()
        var totalDrag = 0f
        detectHorizontalDragGestures(
            onDragStart = { totalDrag = 0f },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                totalDrag += dragAmount
            },
            onDragEnd = {
                when {
                    totalDrag <= -threshold -> viewModel.next()
                    totalDrag >= threshold -> viewModel.previous()
                }
            },
        )
    }

@Composable
private fun AlbumArtDisplay(albumArt: ByteArray?, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val bitmap = remember(albumArt) {
            albumArt?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
        if (bitmap != null) {
            Image(bitmap, contentDescription = "Cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text("♪", style = MaterialTheme.typography.displayLarge)
        }
    }
}

@Composable
private fun TrackInfo(video: YtVideo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            video.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            modifier = Modifier.basicMarquee(animationMode = MarqueeAnimationMode.Immediately),
        )
        video.uploader?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.basicMarquee(animationMode = MarqueeAnimationMode.Immediately),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
