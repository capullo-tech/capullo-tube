package tech.capullo.tubecapullo.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.ui.shared.BroadcastRow
import tech.capullo.tubecapullo.ui.shared.NowPlayingBar
import tech.capullo.tubecapullo.util.formatDuration

/**
 * Launch screen: recommendation cards, the Jetispot home pattern - sections with a header and a
 * horizontal card row (ui reference: Jetispot's SectionComponentBinder/MediumCard, 160dp, 16dp
 * content padding, 12dp spacing). Tapping a card IS the play action: it seeds a station exactly
 * like a search-row tap and opens the player, so a card needs no separate play button (Jetispot
 * cards navigate; these do not).
 *
 * v1 shipped the trending-music kiosk - the cold-start source (probe: full 30 items, all with
 * art, all in the station's duration window; docs/probes/trending-music-results.md). M-B added
 * the "Because you played" section from Room-persisted play history, above the kiosk when
 * non-empty (personal before generic) and absent on first launch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSearch: () -> Unit,
    onOpenPlayer: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val snapcastState by viewModel.snapcastState.collectAsStateWithLifecycle()
    val likedIds by viewModel.likedIds.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.toast.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TubeCapullo") },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
            )
        },
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
        when {
            uiState.isLoading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            else -> LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                uiState.error?.let { error ->
                    // Stale cards stay visible under a retry banner; the network error is not a
                    // reason to blank the screen (and BotCheck deserves its distinct wording,
                    // which HomeViewModel.readable already carries).
                    item(key = "error") { ErrorBanner(error, onRetry = viewModel::load) }
                }
                // Personal before generic: "Because you played" (persisted play history, M-B)
                // sits above the kiosk row and only exists once something has been played.
                if (uiState.history.isNotEmpty()) {
                    item(key = "history-header") { SectionHeader("Because you played") }
                    item(key = "history-row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(uiState.history, key = { it.videoId }) { video ->
                                MusicCard(
                                    video = video,
                                    isPlaying = video.videoId == playerState.video?.videoId,
                                    onPlay = {
                                        viewModel.play(video)
                                        onOpenPlayer()
                                    },
                                )
                            }
                        }
                    }
                }
                item(key = "trending-header") { SectionHeader("Trending music") }
                item(key = "trending-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(uiState.trending, key = { it.videoId }) { video ->
                            MusicCard(
                                video = video,
                                isPlaying = video.videoId == playerState.video?.videoId,
                                onPlay = {
                                    viewModel.play(video)
                                    onOpenPlayer()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * The Jetispot medium card: 160dp art, title, uploader. The tonal MusicNote placeholder sits
 * UNDER the Coil image, so a slow load shows the placeholder and not a flicker (Jetispot's
 * PreviewableAsyncImage pattern, without its preview long-press).
 */
@Composable
private fun MusicCard(video: YtVideo, isPlaying: Boolean, onPlay: () -> Unit) {
    Column(
        Modifier
            .width(160.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onPlay),
    ) {
        Box(
            Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
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
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = "Playing",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
            }
        }
        Text(
            video.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            listOfNotNull(video.uploader, formatDuration(video.durationSeconds))
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun ErrorBanner(error: String, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
