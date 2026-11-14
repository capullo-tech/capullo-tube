package tech.capullo.tubecapullo.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import tech.capullo.tubecapullo.data.backup.BackupProgress
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.ui.shared.CompactListItem
import tech.capullo.tubecapullo.ui.shared.SongActions
import tech.capullo.tubecapullo.ui.shared.SongActionsBox
import tech.capullo.tubecapullo.ui.theme.Dimens
import tech.capullo.tubecapullo.util.TitleCleaner
import tech.capullo.tubecapullo.util.formatDuration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class LibraryTab(val label: String) {
    SONGS("Songs"),
    ALBUMS("Albums"),
    HISTORY("History"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenPlayer: () -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val liked by viewModel.liked.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val playingId = playerState.video?.videoId
    val backupProgress by viewModel.backupProgress.collectAsStateWithLifecycle()
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(LibraryTab.SONGS.ordinal) }
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
        playNext = playNext.takeIf { playingId != null },
    )

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME),
    ) { uri -> uri?.let(viewModel::exportLiked) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importLiked) }

    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            snackbarHostState.showSnackbar(it.text, duration = SnackbarDuration.Long)
            viewModel.backupMessageShown(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    if (selectedTab != LibraryTab.HISTORY.ordinal && backupProgress == null) {
                        LikedOverflowMenu(
                            canExport = !liked.isNullOrEmpty() || !albums.isNullOrEmpty(),
                            onExport = {
                                val date = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                                exportLauncher.launch("capullotube-liked-\$date.json")
                            },
                            onImport = { importLauncher.launch(IMPORT_MIMES) },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            backupProgress?.let { BackupProgressBar(it) }
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
private fun LikedOverflowMenu(canExport: Boolean, onExport: () -> Unit, onImport: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (canExport) {
                DropdownMenuItem(
                    text = { Text("Export liked songs and albums") },
                    onClick = {
                        expanded = false
                        onExport()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("Import liked songs and albums") },
                onClick = {
                    expanded = false
                    onImport()
                },
            )
        }
    }
}

@Composable
private fun BackupProgressBar(progress: BackupProgress) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            when (progress) {
                BackupProgress.Exporting -> "Exporting liked songs and albums"
                BackupProgress.Reading -> "Reading the file"
                is BackupProgress.Resolving -> "Importing ${progress.current} of ${progress.total}"
                BackupProgress.Saving -> "Saving liked songs"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fill = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
        if (progress is BackupProgress.Resolving) {
            LinearProgressIndicator(
                progress = { progress.current.toFloat() / progress.total },
                modifier = fill,
            )
        } else {
            LinearProgressIndicator(modifier = fill)
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
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(label, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

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
                        contentDescription = null,
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

private const val PLAYING_SCRIM = 0.4f

private const val BACKUP_MIME = "application/json"

private val IMPORT_MIMES = arrayOf(BACKUP_MIME, "text/plain", "*/*")

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
