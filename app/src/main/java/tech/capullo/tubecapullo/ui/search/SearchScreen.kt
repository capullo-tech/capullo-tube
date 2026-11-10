package tech.capullo.tubecapullo.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.ui.shared.BroadcastRow
import tech.capullo.tubecapullo.ui.shared.NowPlayingBar
import tech.capullo.tubecapullo.util.formatDuration

/**
 * Search YouTube and tap a row to play it - the M2 slice. The seed-a-station flow (M4) will grow
 * out of this screen: today a tap plays one track, later it starts a station.
 *
 * Deliberately one text field for all three inputs (search terms, a video link, a playlist link):
 * the parsing that tells them apart is already exact (`YouTubeUrls`), so making the user pick a
 * mode first would be asking them to answer a question the app can answer itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenPlayer: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    // Set only when Search is stacked on Home; as the launch screen, system back exits as before.
    BackHandler(enabled = onBack != null) { onBack?.invoke() }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val snapcastState by viewModel.snapcastState.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedIds.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.toast.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("TubeCapullo") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            playerState.video?.let { video ->
                Column {
                    if (snapcastState.isBroadcasting) {
                        BroadcastRow(
                            httpPort = snapcastState.broadcastHttpPort,
                            listenerCount = snapcastState.groups
                                .flatMap { it.clients }
                                .count { it.connected },
                        )
                    }
                    NowPlayingBar(
                        video = video,
                        state = playerState,
                        isLiked = video.videoId in likedIds,
                        onToggleLike = viewModel::toggleLike,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onNext = viewModel::next,
                        onOpen = onOpenPlayer,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("Search YouTube, or paste a link") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = viewModel::submit) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
            )

            uiState.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    uiState.isSearching -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                    uiState.results.isEmpty() -> Text(
                        "Search for a song, or paste a video or playlist link",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(uiState.results, key = { it.videoId }) { video ->
                            ResultRow(
                                video = video,
                                isPlaying = video.videoId == playerState.video?.videoId,
                                onPlay = {
                                    viewModel.play(video)
                                    onOpenPlayer()
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    video: YtVideo,
    isPlaying: Boolean,
    onPlay: () -> Unit,
) {
    ListItem(
        leadingContent = {
            // No thumbnails yet: nothing in this app loads remote images, and pulling in an image
            // loader for a search list isn't worth it before album art is wired up (same call
            // telecloud made at this stage).
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
            )
        },
        headlineContent = { Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                listOfNotNull(video.uploader, formatDuration(video.durationSeconds))
                    .joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (isPlaying) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = "Playing",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        modifier = Modifier.clickable(onClick = onPlay),
    )
}
