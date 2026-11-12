package tech.capullo.tubecapullo.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.ui.shared.CompactListItem
import tech.capullo.tubecapullo.ui.shared.SongActions
import tech.capullo.tubecapullo.ui.shared.SongActionsBox
import tech.capullo.tubecapullo.ui.theme.Dimens
import tech.capullo.tubecapullo.util.TitleCleaner
import tech.capullo.tubecapullo.util.formatDuration

private enum class LibraryTab(val label: String) {
    SONGS("Songs"),
    ALBUMS("Albums"),
    HISTORY("History"),
}

/**
 * The Library tab (PLAN "Home v2 and albums"): Songs (the liked songs: Play, Shuffle, Radio;
 * unlike from the row), Albums (the liked albums; a tap plays one as an album, the heart removes
 * it) and History. A song row tap is the app-wide track tap (Go to radio: song radio + open the
 * player); a long-press opens the song's actions: Play next (only while something plays) and Go
 * to radio. A liked album in History plays as an album. The shell owns the bottom navigation, and mini-bar, so this screen renders only its
 * top bar and content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenPlayer: () -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val liked by viewModel.liked.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val playingId = playerState.video?.videoId
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Saveable: the selected tab survives rotation and process death (CONTRIBUTING §3).
    var selectedTab by rememberSaveable { mutableIntStateOf(LibraryTab.SONGS.ordinal) }
    // One list state per tab, hoisted here so switching tabs keeps each tab's scroll position.
    val likedListState = rememberLazyListState()
    val albumsListState = rememberLazyListState()
    val historyListState = rememberLazyListState()
    val playAlbum: (YtVideo) -> Unit = { album ->
        viewModel.playAlbum(album)
        onOpenPlayer()
    }

    val playNext: (YtVideo) -> Unit = { video ->
        viewModel.playNext(video)
        scope.launch { snackbarHostState.showSnackbar("Plays next") }
    }
    val actions = SongActions(
        goToRadio = { video ->
            viewModel.playSongRadio(video)
            onOpenPlayer()
        },
        // Hidden while nothing plays: there is no station to queue into.
        playNext = playNext.takeIf { playingId != null },
    )

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Library") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                LibraryTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab.ordinal,
                        onClick = { selectedTab = tab.ordinal },
                        text = { Text(tab.label) },
                    )
                }
            }
            when (LibraryTab.entries[selectedTab]) {
                LibraryTab.SONGS -> LikedTab(
                    tracks = liked,
                    playingId = playingId,
                    listState = likedListState,
                    onPlayLiked = { shuffle ->
                        viewModel.playLiked(shuffle)
                        onOpenPlayer()
                    },
                    onPlayLikedRadio = {
                        viewModel.playLikedRadio()
                        onOpenPlayer()
                    },
                    actions = actions,
                    onUnlike = viewModel::unlike,
                )
                LibraryTab.ALBUMS -> AlbumsTab(
                    albums = albums,
                    playingId = playingId,
                    listState = albumsListState,
                    onPlay = playAlbum,
                    onUnlike = viewModel::unlikeAlbum,
                )
                LibraryTab.HISTORY -> HistoryTab(
                    rows = history,
                    playingId = playingId,
                    listState = historyListState,
                    actions = actions,
                    onPlayAlbum = playAlbum,
                )
            }
        }
    }
}

@Composable
private fun LikedTab(
    tracks: List<YtVideo>?,
    playingId: String?,
    listState: LazyListState,
    onPlayLiked: (shuffle: Boolean) -> Unit,
    onPlayLikedRadio: () -> Unit,
    actions: SongActions,
    onUnlike: (YtVideo) -> Unit,
) {
    when {
        tracks == null -> Loading()
        // No likes: the actions are hidden, not disabled (CONTRIBUTING §3), and the empty state
        // says how to get out of it.
        tracks.isEmpty() -> EmptyState(
            "No liked songs yet. Tap the heart on the player or on the now-playing bar to " +
                "like the song that is playing. Liked songs show up here.",
        )
        else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item(key = "liked-actions") {
                LikedActions(
                    count = tracks.size,
                    onPlay = { onPlayLiked(false) },
                    onShuffle = { onPlayLiked(true) },
                    onRadio = onPlayLikedRadio,
                )
            }
            items(tracks, key = { it.videoId }) { video ->
                TrackRow(
                    video = video,
                    isPlaying = video.videoId == playingId,
                    actions = actions,
                    trailing = {
                        IconButton(
                            onClick = { onUnlike(video) },
                            modifier = Modifier.size(Dimens.LIST_BUTTON_SIZE),
                        ) {
                            Icon(
                                Icons.Default.Favorite,
                                contentDescription = "Unlike",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(Dimens.LIST_ICON_SIZE),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun AlbumsTab(
    albums: List<YtVideo>?,
    playingId: String?,
    listState: LazyListState,
    onPlay: (YtVideo) -> Unit,
    onUnlike: (YtVideo) -> Unit,
) {
    when {
        albums == null -> Loading()
        albums.isEmpty() -> EmptyState(
            "No liked albums yet. Play a full album, for example from Full albums on Home, " +
                "and tap the heart. Liked albums show up here.",
        )
        else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(albums, key = { it.videoId }) { album ->
                AlbumRow(album, album.videoId == playingId, onPlay) {
                    IconButton(
                        onClick = { onUnlike(album) },
                        modifier = Modifier.size(Dimens.LIST_BUTTON_SIZE),
                    ) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = "Remove album",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Dimens.LIST_ICON_SIZE),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(
    rows: List<LibraryRow>?,
    playingId: String?,
    listState: LazyListState,
    actions: SongActions,
    onPlayAlbum: (YtVideo) -> Unit,
) {
    when {
        rows == null -> Loading()
        rows.isEmpty() -> EmptyState(
            "Nothing played yet. Songs you play show up here, most recent first.",
        )
        else -> LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(rows, key = { it.video.videoId }) { row ->
                val isPlaying = row.video.videoId == playingId
                if (row.isAlbum) {
                    AlbumRow(row.video, isPlaying, onPlayAlbum, trailing = null)
                } else {
                    TrackRow(video = row.video, isPlaying = isPlaying, actions = actions)
                }
            }
        }
    }
}

/** An album row: a tap plays it as an album; no song menu (Play next and Go to radio are song
 *  actions). */
@Composable
private fun AlbumRow(
    album: YtVideo,
    isPlaying: Boolean,
    onPlay: (YtVideo) -> Unit,
    trailing: (@Composable () -> Unit)?,
) {
    Box(Modifier.clickable(onClickLabel = "Play album") { onPlay(album) }) {
        TrackRowContent(album, isPlaying, trailing)
    }
}

@Composable
private fun LikedActions(
    count: Int,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRadio: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            if (count == 1) "1 song" else "$count songs",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            ActionButton("Play", Icons.Default.PlayArrow, onPlay)
            ActionButton("Shuffle", Icons.Default.Shuffle, onShuffle)
            ActionButton("Radio", Icons.Default.Radio, onRadio)
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
    ) {
        // Decorative: the button's text label carries the meaning.
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(label, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

/**
 * One track: art, cleaned title, uploader and duration. The playing track gets the GraphicEq
 * mark over its art, the same highlight the Home cards use. The MusicNote placeholder sits under
 * the Coil image so a slow load shows the placeholder, not a flicker (Home's MusicCard pattern).
 */
@Composable
private fun TrackRow(
    video: YtVideo,
    isPlaying: Boolean,
    actions: SongActions,
    trailing: (@Composable () -> Unit)? = null,
) {
    SongActionsBox(video, actions) { TrackRowContent(video, isPlaying, trailing) }
}

@Composable
private fun TrackRowContent(
    video: YtVideo,
    isPlaying: Boolean,
    trailing: (@Composable () -> Unit)?,
) {
    val title = remember(video.title, video.uploader) {
        TitleCleaner.clean(video.title, video.uploader)
    }
    CompactListItem(
        leadingContent = {
            Box(
                Modifier
                    .size(Dimens.LIST_ART_SIZE)
                    .clip(RoundedCornerShape(Dimens.LIST_ART_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(Dimens.LIST_ART_NOTE_SIZE),
                )
                video.thumbnailUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null, // decorative; the title text carries the meaning
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isPlaying) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                MaterialTheme.colorScheme.scrim.copy(alpha = PLAYING_SCRIM),
                            ),
                    )
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = "Playing",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        },
        headlineContent = {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                listOfNotNull(video.uploader, formatDuration(video.durationSeconds))
                    .joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = trailing,
        headlineColor = if (isPlaying) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
}

/**
 * Alpha of the scrim behind the GraphicEq mark on the playing row's art, so the primary-tinted
 * mark stays legible on bright thumbnails. Chosen by eye, not measured on a device (GUESS). Too
 * low and the mark disappears on light art; too high and the art reads as missing.
 */
private const val PLAYING_SCRIM = 0.4f

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize()) {
        CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(32.dp),
        )
    }
}
