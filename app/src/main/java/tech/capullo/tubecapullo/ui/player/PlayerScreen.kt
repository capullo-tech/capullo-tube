package tech.capullo.tubecapullo.ui.player

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import tech.capullo.tubecapullo.R
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.RepeatMode
import tech.capullo.tubecapullo.station.StationState
import tech.capullo.tubecapullo.ui.shared.LikeButton
import tech.capullo.tubecapullo.ui.shared.SONG_OPTIONS
import tech.capullo.tubecapullo.ui.shared.SongActionsMenu
import tech.capullo.tubecapullo.ui.theme.Dimens
import tech.capullo.tubecapullo.ui.upnext.UpNextSheet
import tech.capullo.tubecapullo.ui.upnext.UpNextText
import tech.capullo.tubecapullo.util.ShareLink
import kotlin.math.abs

private val ART_SWIPE_THRESHOLD = 90.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onOpenMultiroom: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.playerState.collectAsStateWithLifecycle()
    val station by viewModel.station.collectAsStateWithLifecycle()
    val snapState by viewModel.snapcastState.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedIds.collectAsStateWithLifecycle()
    val likedAlbumIds by viewModel.likedAlbumIds.collectAsStateWithLifecycle()
    val albumArt by viewModel.albumArt.collectAsStateWithLifecycle()
    var upNextOpen by rememberSaveable { mutableStateOf(false) }
    val likeChip = rememberLikeChipState(viewModel.likeEffects, viewModel.rollOvers)

    if (upNextOpen) UpNextSheet(onDismiss = { upNextOpen = false })

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { StationTitle(station) },
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
                    station = station,
                    albumArt = albumArt,
                    isLiked = video.videoId in (if (station.albumMode) likedAlbumIds else likedIds),
                    viewModel = viewModel,
                    connectedOthers = clients.count { it.connected && it.id != viewModel.localClientId },
                    anyConnected = clients.any { it.connected },
                    likeChip = likeChip,
                    onOpenClients = onOpenMultiroom,
                    onOpenUpNext = { upNextOpen = true },
                )
            }
        }
    }
}

@Composable
private fun StationTitle(station: StationState) {
    val source = station.source ?: return
    val title = UpNextText.sourceTitle(source)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title.word, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            "·",
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(
            title.name,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
        )
    }
}

@Composable
private fun PortraitPlayer(
    video: YtVideo,
    state: PlaybackCoordinator.State,
    station: StationState,
    albumArt: ByteArray?,
    isLiked: Boolean,
    viewModel: PlayerViewModel,
    connectedOthers: Int,
    anyConnected: Boolean,
    likeChip: LikeChipState,
    onOpenClients: () -> Unit,
    onOpenUpNext: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            AlbumArtDisplay(
                albumArt,
                Modifier.size(minOf(maxWidth, maxHeight)).artGestures(viewModel, onOpenUpNext),
            )
            LikeChip(likeChip, Modifier.align(Alignment.BottomCenter))
        }
        Spacer(Modifier.height(16.dp))
        TrackInfo(
            video,
            onChannelClick = { viewModel.startChannel(video) },
            onStartRadio = viewModel::startRadio.takeIf {
                station.source !is PlaySource.SongRadio && station.source !is PlaySource.SearchRadio
            },
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        station.album?.let { album ->
            album.chapters.getOrNull(album.index)?.let { chapter ->
                Text(
                    "Chapter ${album.index + 1} of ${album.chapters.size} · ${chapter.title}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    modifier = Modifier
                        .padding(horizontal = 28.dp, vertical = 4.dp)
                        .basicMarquee(),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            val durationMs = state.durationMs.takeIf { it > 0 } ?: (video.durationSeconds * 1000)
            SeekBar(durationMs, viewModel.position, onSeek = viewModel::seekTo)
            ControlBar(state, station, viewModel)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onOpenClients) { MultiroomIcon(connectedOthers, anyConnected) }
                LikeButton(
                    isLiked = isLiked,
                    likeLabel = if (station.albumMode) "Save album" else "Like - more like this",
                    onLike = viewModel::toggleLike,
                )
                IconButton(onClick = { ShareLink.share(context, video) }) {
                    Icon(Icons.Default.Share, contentDescription = "Share song")
                }
                IconButton(onClick = onOpenUpNext) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
                }
            }
        }
    }
}

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
    val position by positionState.collectAsStateWithLifecycle()
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

private val CONTROL_GAP = 4.dp

private val ORDER_ICON_SIZE = 26.dp

@Composable
private fun ControlBar(
    state: PlaybackCoordinator.State,
    station: StationState,
    viewModel: PlayerViewModel,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(CONTROL_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        IconButton(
            onClick = viewModel::toggleShuffle,
            modifier = Modifier.size(Dimens.PLAYER_ORDER_BUTTON_SIZE),
        ) {
            Icon(
                Icons.Default.Shuffle,
                contentDescription = if (station.shuffle) "Shuffle on" else "Shuffle off",
                modifier = Modifier.size(ORDER_ICON_SIZE),
                tint = toggleTint(station.shuffle),
            )
        }
        IconButton(onClick = viewModel::previous, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(40.dp))
        }
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
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Next",
                modifier = Modifier.size(40.dp),
            )
        }
        if (station.isCollection) {
            val one = station.repeat == RepeatMode.ONE
            IconButton(
                onClick = viewModel::cycleRepeat,
                modifier = Modifier.size(Dimens.PLAYER_ORDER_BUTTON_SIZE),
            ) {
                Icon(
                    if (one) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = when (station.repeat) {
                        RepeatMode.OFF -> "Repeat off"
                        RepeatMode.ALL -> "Repeat all"
                        RepeatMode.ONE -> "Repeat one"
                    },
                    modifier = Modifier.size(ORDER_ICON_SIZE),
                    tint = toggleTint(station.repeat != RepeatMode.OFF),
                )
            }
        } else {
            Spacer(Modifier.size(Dimens.PLAYER_ORDER_BUTTON_SIZE))
        }
    }
}

@Composable
private fun toggleTint(active: Boolean): Color = if (active) {
    MaterialTheme.colorScheme.primary
} else {
    MaterialTheme.colorScheme.onSurfaceVariant
}

private fun Modifier.artGestures(viewModel: PlayerViewModel, onSwipeUp: () -> Unit): Modifier = this
    .pointerInput(Unit) { detectTapGestures(onTap = { viewModel.togglePlayPause() }) }
    .pointerInput(Unit) {
        val threshold = ART_SWIPE_THRESHOLD.toPx()
        var dx = 0f
        var dy = 0f
        detectDragGestures(
            onDragStart = {
                dx = 0f
                dy = 0f
            },
            onDrag = { change, dragAmount ->
                change.consume()
                dx += dragAmount.x
                dy += dragAmount.y
            },
            onDragEnd = {
                val horizontal = abs(dx) >= abs(dy)
                when {
                    horizontal && dx <= -threshold -> viewModel.next()
                    horizontal && dx >= threshold -> viewModel.previous()
                    !horizontal && dy <= -threshold -> onSwipeUp()
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
private fun TrackInfo(
    video: YtVideo,
    onChannelClick: () -> Unit,
    onStartRadio: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val title = remember(video.title, video.uploader) { UpNextText.clean(video) }
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            val hasActions = onStartRadio != null
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier
                    .then(
                        if (hasActions) {
                            Modifier.clickable(onClickLabel = SONG_OPTIONS) { menuOpen = true }
                        } else {
                            Modifier
                        },
                    )
                    .basicMarquee(animationMode = MarqueeAnimationMode.Immediately),
            )
            SongActionsMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onPlayNext = null,
                onGoToRadio = onStartRadio,
            )
        }
        video.uploader?.let { uploader ->
            Text(
                uploader,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clickable(onClickLabel = "Play this channel", onClick = onChannelClick)
                    .basicMarquee(animationMode = MarqueeAnimationMode.Immediately),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
