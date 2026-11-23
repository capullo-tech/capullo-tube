package tech.capullo.tubecapullo.ui.upnext

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.ui.shared.CompactListItem
import tech.capullo.tubecapullo.ui.shared.LikeButton
import tech.capullo.tubecapullo.ui.shared.PlayerDrawer
import tech.capullo.tubecapullo.ui.shared.SongActionsMenu
import tech.capullo.tubecapullo.ui.theme.Dimens
import tech.capullo.tubecapullo.util.ShareLink
import tech.capullo.tubecapullo.util.formatDuration
import tech.capullo.tubecapullo.util.viewsLine

private class RowActions(
    val playNow: (YtVideo, Boolean) -> Unit,
    val remove: (YtVideo) -> Unit,
    val share: (YtVideo) -> Unit,
    val toggleLike: (YtVideo) -> Unit,
    val toggleHeart: (YtVideo) -> Unit,
    val startRadio: (YtVideo) -> Unit,
    val playChapter: (Long) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpNextSheet(onDismiss: () -> Unit, viewModel: UpNextViewModel = hiltViewModel()) {
    val station by viewModel.station.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedIds.collectAsStateWithLifecycle()
    val likedAlbumIds by viewModel.likedAlbumIds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { text ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(text)
        }
    }
    val actions = RowActions(
        playNow = viewModel::playNow,
        remove = {
            viewModel.remove(it.videoId)
            say("Removed from the queue")
        },
        share = { ShareLink.share(context, it) },
        toggleLike = viewModel::toggleLike,
        toggleHeart = viewModel::toggleHeart,
        startRadio = {
            viewModel.startRadio(it)
            onDismiss()
        },
        playChapter = viewModel::seekTo,
    )
    val entries = remember(station) { queueEntries(station) }
    val stationSeedId = UpNextText.stationSeedId(station.source)
    // The rows on screen: the station's list, or, during a drag, the order being dragged.
    var rows by remember { mutableStateOf(entries) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(entries) { if (!dragging) rows = entries }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val movable = upcomingPosition(rows, from.key) != null && upcomingPosition(rows, to.key) != null
        if (movable) rows = rows.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    var userMoved by remember { mutableStateOf(false) }
    var bottomSlackPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions
            .filterIsInstance<DragInteraction.Start>()
            .collect { userMoved = true }
    }
    // The playing song goes to the top when the sheet opens and whenever the song changes
    // (a tap on a row, Next); between changes your own scrolling is left alone.
    var shownPlaying by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(entries) {
        val playingId = station.current?.videoId
        if (playingId != shownPlaying) {
            shownPlaying = playingId
            userMoved = false
        }
        if (userMoved || dragging) return@LaunchedEffect
        val target = initialIndex(entries)
        listState.scrollToItem(target)
        val info = listState.layoutInfo
        if (listState.firstVisibleItemIndex < target) {
            val below = info.visibleItemsInfo.filter { it.index >= target }.sumOf { it.size }
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            bottomSlackPx = (viewport - below).coerceAtLeast(0)
            withFrameNanos { }
            listState.scrollToItem(target)
        }
    }

    PlayerDrawer(onDismiss = onDismiss, showHandle = false, twoSteps = false) {
        Column {
            QueueTitle(station.source)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    bottom = with(LocalDensity.current) { bottomSlackPx.toDp() },
                ),
            ) {
                items(rows, key = { it.key }, contentType = { it::class }) { entry ->
                    when (entry) {
                        is EndLine -> EndLineText(entry.text)
                        is ChapterEntry -> ChapterRow(entry, actions)
                        is TrackEntry -> ReorderableItem(reorder, key = entry.key, enabled = entry.removable) { isDragging ->
                            TrackRow(
                                track = entry,
                                isLiked = entry.video.videoId in if (
                                    entry.state == RowState.CURRENT && station.albumMode
                                ) {
                                    likedAlbumIds
                                } else {
                                    likedIds
                                },
                                isCollection = station.isCollection,
                                stationSeedId = stationSeedId,
                                isDragging = isDragging,
                                actions = actions,
                                dragModifier = Modifier.longPressDraggableHandle(
                                    enabled = entry.removable,
                                    onDragStarted = { dragging = true },
                                    onDragStopped = {
                                        dragging = false
                                        upcomingPosition(rows, entry.key)?.let { viewModel.move(entry.video.videoId, it) }
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun QueueTitle(source: PlaySource?) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp)) {
        Text("Queue", style = MaterialTheme.typography.titleLarge)
        source?.let {
            val title = UpNextText.sourceTitle(it)
            Text(
                "${title.word} · ${title.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun contentAlpha(state: RowState): Float = if (state == RowState.PLAYED) {
    Dimens.UP_NEXT_PLAYED_ALPHA
} else {
    1f
}

@Composable
private fun containerColor(state: RowState): Color = if (state == RowState.CURRENT) {
    MaterialTheme.colorScheme.secondaryContainer
} else {
    ListItemDefaults.containerColor
}

@Composable
private fun headlineColor(state: RowState): Color = if (state == RowState.CURRENT) {
    MaterialTheme.colorScheme.primary
} else {
    MaterialTheme.colorScheme.onSurface
}

private fun Modifier.rowTap(state: RowState, onPlay: () -> Unit): Modifier = when (state) {
    RowState.PLAYED -> clickable(onClickLabel = "Play again", onClick = onPlay)
        .semantics { stateDescription = "Played" }
    RowState.CURRENT -> semantics { stateDescription = "Now playing" }
    RowState.UPCOMING -> clickable(onClickLabel = "Play now", onClick = onPlay)
}

@Composable
private fun ChapterRow(chapter: ChapterEntry, actions: RowActions) {
    val alpha = contentAlpha(chapter.state)
    CompactListItem(
        leadingContent = {
            Box(
                Modifier.size(Dimens.LIST_ART_SIZE).alpha(alpha),
                contentAlignment = Alignment.Center,
            ) {
                if (chapter.state == RowState.CURRENT) {
                    PlayingBadge()
                } else {
                    Text(
                        chapter.number.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        headlineContent = {
            Text(
                chapter.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(alpha),
            )
        },
        supportingContent = {
            Text(
                "Chapter · ${formatDuration(chapter.startMs / 1000) ?: "0:00"}",
                maxLines = 1,
                modifier = Modifier.alpha(alpha),
            )
        },
        headlineColor = headlineColor(chapter.state),
        containerColor = containerColor(chapter.state),
        modifier = Modifier.rowTap(chapter.state) { actions.playChapter(chapter.startMs) },
    )
}

@Composable
private fun EndLineText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TrackRow(
    track: TrackEntry,
    isLiked: Boolean,
    isCollection: Boolean,
    stationSeedId: String?,
    isDragging: Boolean,
    actions: RowActions,
    dragModifier: Modifier,
) {
    val video = track.video
    val title = remember(video.title, video.uploader) { UpNextText.clean(video) }
    val isCurrent = track.state == RowState.CURRENT
    val alpha = contentAlpha(track.state)
    val row = @Composable {
        CompactListItem(
            leadingContent = { Thumbnail(video.thumbnailUrl, track.state) },
            headlineContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.alpha(alpha),
                ) {
                    if (track.pinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = "Pinned",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp).size(14.dp),
                        )
                    }
                    Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            supportingContent = {
                Column(Modifier.alpha(alpha)) {
                    video.uploader?.let {
                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    viewsLine(video)?.let {
                        Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    track.why?.let {
                        Text(
                            UpNextText.provenance(it, video, stationSeedId, track.blended),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            trailingContent = { RowButtons(video, isCurrent, isLiked, isCollection, actions) },
            headlineColor = headlineColor(track.state),
            containerColor = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHighest else containerColor(track.state),
            modifier = dragModifier
                .rowTap(track.state) {
                    actions.playNow(video, track.state == RowState.PLAYED)
                }
                .then(
                    if (track.removable) {
                        Modifier.semantics {
                            customActions = listOf(
                                CustomAccessibilityAction("Remove") {
                                    actions.remove(video)
                                    true
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        )
    }
    if (track.removable) {
        SwipeToRemove(video.videoId, onRemove = { actions.remove(video) }, content = row)
    } else {
        row()
    }
}

@Composable
private fun SwipeToRemove(videoId: String, onRemove: () -> Unit, content: @Composable () -> Unit) {
    key(videoId) {
        // A deliberate swipe, not a scroll that drifts sideways: past 40 % of the row's width.
        val state = rememberSwipeToDismissBoxState(positionalThreshold = { width -> width * SWIPE_REMOVE_FRACTION })
        SwipeToDismissBox(
            state = state,
            backgroundContent = {
                if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    RemoveBackground()
                }
            },
            enableDismissFromStartToEnd = false,
            onDismiss = { onRemove() },
        ) {
            content()
        }
    }
}

@Composable
private fun RemoveBackground() {
    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
    ) {
        Icon(
            Icons.Default.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun RowButtons(
    video: YtVideo,
    isCurrent: Boolean,
    isLiked: Boolean,
    isCollection: Boolean,
    actions: RowActions,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LikeButton(
            isLiked = isLiked,
            likeLabel = "Like",
            onLike = { if (isCurrent) actions.toggleHeart(video) else actions.toggleLike(video) },
            modifier = Modifier.size(Dimens.LIST_BUTTON_SIZE),
            iconModifier = Modifier.size(Dimens.LIST_ICON_SIZE),
        )
        IconButton(
            onClick = { actions.share(video) },
            modifier = Modifier.size(Dimens.LIST_BUTTON_SIZE),
        ) {
            Icon(
                Icons.Default.Share,
                contentDescription = "Share song",
                modifier = Modifier.size(Dimens.LIST_ICON_SIZE),
            )
        }
        if (isCollection) RowMenu(video, actions)
    }
}

@Composable
private fun RowMenu(video: YtVideo, actions: RowActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(Dimens.LIST_BUTTON_SIZE)) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "More actions",
                modifier = Modifier.size(Dimens.LIST_ICON_SIZE),
            )
        }
        SongActionsMenu(
            expanded = open,
            onDismiss = { open = false },
            onPlayNext = null,
            onGoToRadio = { actions.startRadio(video) },
        )
    }
}

@Composable
private fun Thumbnail(url: String?, state: RowState) {
    Box(
        Modifier
            .size(Dimens.LIST_ART_SIZE)
            .alpha(contentAlpha(state))
            .clip(RoundedCornerShape(Dimens.LIST_ART_CORNER))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.LIST_ART_NOTE_SIZE),
        )
        url?.let {
            AsyncImage(
                model = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PlayingBadge() {
    Box(
        Modifier
            .size(Dimens.UP_NEXT_PLAYING_BADGE_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Default.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(Dimens.LIST_ART_NOTE_SIZE),
        )
    }
}

private const val SWIPE_REMOVE_FRACTION = 0.4f
