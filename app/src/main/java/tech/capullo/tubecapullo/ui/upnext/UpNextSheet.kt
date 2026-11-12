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

/** Row actions; each takes the row's video. */
private class RowActions(
    /** A row tap; the flag is set for a played row (play it again, then go on). */
    val playNow: (YtVideo, Boolean) -> Unit,
    /** An upcoming row's swipe, or its "Remove" accessibility action. */
    val remove: (YtVideo) -> Unit,
    val share: (YtVideo) -> Unit,
    val toggleLike: (YtVideo) -> Unit,
    /** The current row's heart: the Player's ([UpNextViewModel.toggleHeart]; the album's in
     *  album mode). */
    val toggleHeart: (YtVideo) -> Unit,
    /** Collections only: leave the list for a song radio seeded from the row. */
    val startRadio: (YtVideo) -> Unit,
    /** A chapter row's tap: the album seeks to its start. */
    val playChapter: (Long) -> Unit,
)

/**
 * The Queue (owner decisions 2026-11-12): "Queue", the station's source title under it, then one
 * list with no section headers ([queueEntries]): the songs played this session (dimmed), the
 * current song (highlighted, a GraphicEq badge on its art), then the upcoming songs in play
 * order (pinned rows marked; in album mode the chapters follow the album's row with the same
 * three states). Each upcoming row says where it came from ([UpNextText.provenance]). The sheet
 * opens with the first upcoming row at the top ([initialIndex]); the played and current rows sit
 * above it. A pull down scrolls the list first and dismisses the sheet only at the list's top
 * (M3's nested scroll). A row tap plays that track now, the common queue behaviour: an upcoming
 * row jumps ahead to it (the station goes on from there), a played row plays again before the
 * station goes on, and the current row's tap does nothing.
 *
 * Each row has the Player's heart (tap likes or unlikes) and a share button; while a collection
 * plays (Liked, a playlist, a channel), a menu adds Go to radio (a song radio from the row), which
 * replaces the station and closes the sheet. A radio row has no menu: Go to radio is its only
 * item, and a radio hides it. An upcoming row swiped toward the start leaves the queue (owner
 * decision 2026-11-11). The sheet is a [PlayerDrawer]: two steps, capped below the Player's top.
 */
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
    // Opened on the first upcoming row. The station can still change while the sheet slides in
    // (the first frames may hold the StateFlow's previous value, and the played list grows as a
    // track starts), so the start row is re-applied on every change UNTIL the listener drags the
    // list; after that their position is theirs (OPPO 2026-11-12: a one-shot scroll on the first
    // frame opened on the current or last played row instead of the next song).
    // A queue shorter than the list cannot scroll that far (LazyColumn stops where its last row
    // meets the bottom: measured idx=3 first=2 on the OPPO with Liked radio's short queue), so
    // the list gets bottom slack of exactly the missing height. The sheet opens half-way (two
    // steps, owner call 2026-11-12), so that slack sits below the screen edge until the listener
    // drags the sheet fully open.
    val listState = rememberLazyListState()
    var userMoved by remember { mutableStateOf(false) }
    var bottomSlackPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions
            .filterIsInstance<DragInteraction.Start>()
            .first()
        userMoved = true
    }
    LaunchedEffect(entries) {
        if (userMoved) return@LaunchedEffect
        val target = initialIndex(entries)
        listState.scrollToItem(target)
        val info = listState.layoutInfo
        if (listState.firstVisibleItemIndex < target) {
            val below = info.visibleItemsInfo.filter { it.index >= target }.sumOf { it.size }
            val viewport = info.viewportEndOffset - info.viewportStartOffset
            bottomSlackPx = (viewport - below).coerceAtLeast(0)
            withFrameNanos { } // let the slack lay out before scrolling again
            listState.scrollToItem(target)
        }
    }

    // A drawer, never full screen, opened half-way (PlayerDrawer).
    PlayerDrawer(onDismiss = onDismiss) {
        Column {
            QueueTitle(station.source)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(
                    bottom = with(LocalDensity.current) { bottomSlackPx.toDp() },
                ),
            ) {
                items(entries, key = { it.key }, contentType = { it::class }) { entry ->
                    when (entry) {
                        is EndLine -> EndLineText(entry.text)
                        is ChapterEntry -> ChapterRow(entry, actions)
                        is TrackEntry -> TrackRow(
                            track = entry,
                            // The current row's heart is the Player's: the album's in album
                            // mode.
                            isLiked = entry.video.videoId in if (
                                entry.state == RowState.CURRENT && station.albumMode
                            ) {
                                likedAlbumIds
                            } else {
                                likedIds
                            },
                            isCollection = station.isCollection,
                            actions = actions,
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

/** "Queue", and under it the station's source as the Player's top bar names it. */
@Composable
private fun QueueTitle(source: PlaySource?) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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

/** A played row's art and text fade; the current and upcoming rows keep full alpha. */
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

/** The row's tap and its spoken state. The current row has no tap (it is already playing), so
 *  a screen reader does not announce it as a disabled button; "Now playing" and "Played" say
 *  what the highlight and the dimming show. */
private fun Modifier.rowTap(state: RowState, onPlay: () -> Unit): Modifier = when (state) {
    RowState.PLAYED -> clickable(onClickLabel = "Play again", onClick = onPlay)
        .semantics { stateDescription = "Played" }
    RowState.CURRENT -> semantics { stateDescription = "Now playing" }
    RowState.UPCOMING -> clickable(onClickLabel = "Play now", onClick = onPlay)
}

/** A chapter of the playing album: its number (the playing badge on the current one), title and
 *  start time in the album. */
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

/** The last line when nothing is still to come. */
@Composable
private fun EndLineText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * One track. The three trailing [Dimens.LIST_BUTTON_SIZE] buttons of a collection row take
 * 120 dp; on a 360 dp wide screen, after the row's padding and gaps and the art, that leaves the
 * text about 148 dp, so every text line keeps its line limit and ellipsizes. A radio row has no
 * menu button, so its text gets 40 dp more.
 */
@Composable
private fun TrackRow(
    track: TrackEntry,
    isLiked: Boolean,
    isCollection: Boolean,
    actions: RowActions,
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
                            UpNextText.provenance(it, video),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            trailingContent = { RowButtons(video, isCurrent, isLiked, isCollection, actions) },
            headlineColor = headlineColor(track.state),
            containerColor = containerColor(track.state),
            modifier = Modifier
                .rowTap(track.state) {
                    actions.playNow(video, track.state == RowState.PLAYED)
                }
                // The swipe is a gesture only; accessibility services get it as an action.
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

/**
 * An upcoming row that leaves the queue when swiped toward the start, over a delete background.
 * End to start only: the other way has no meaning here, and a stray rightward drag must not
 * remove a track.
 */
@Composable
private fun SwipeToRemove(videoId: String, onRemove: () -> Unit, content: @Composable () -> Unit) {
    // Rows are keyed by position, so after a removal this slot shows the next track, which must
    // start settled instead of inheriting the dismissed offset.
    key(videoId) {
        val state = rememberSwipeToDismissBoxState()
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
            contentDescription = null, // decorative; the row's "Remove" action carries the meaning
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** The row's own buttons: the heart, share, then (collections only) the song menu. */
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
        // Hidden, not disabled, in a radio (CONTRIBUTING §3): its one item, Go to radio, would
        // only replace a radio with another.
        if (isCollection) RowMenu(video, actions)
    }
}

/** Only the song actions: Remove is the swipe and Play next is the row tap's job (it plays now),
 *  which leaves Go to radio. */
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

/** Placeholder note under the Coil image, so a slow load shows the note, not a flicker (the
 *  Library row's pattern). A played row's art fades; the current row's carries the playing
 *  badge. */
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
                contentDescription = null, // decorative; the title text carries the meaning
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (state == RowState.CURRENT) PlayingBadge()
    }
}

/** The GraphicEq mark on a filled primary circle: the current row's art, or the playing
 *  chapter's number slot. Decorative: the row's "Now playing" state description carries the
 *  meaning, so a screen reader does not say it twice. */
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
