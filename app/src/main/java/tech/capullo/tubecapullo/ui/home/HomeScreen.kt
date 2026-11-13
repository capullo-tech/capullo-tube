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
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.ui.shared.SongActions
import tech.capullo.tubecapullo.ui.shared.SongActionsBox
import tech.capullo.tubecapullo.ui.theme.Dimens
import tech.capullo.tubecapullo.ui.upnext.UpNextText
import tech.capullo.tubecapullo.util.TitleCleaner
import tech.capullo.tubecapullo.util.countryName
import tech.capullo.tubecapullo.util.formatDuration
import tech.capullo.tubecapullo.util.viewsLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPlayer: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by viewModel.playerState.collectAsStateWithLifecycle()
    val playingId = playerState.video?.videoId
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.toast.collect { snackbarHostState.showSnackbar(it) }
    }

    val playNext: (YtVideo) -> Unit = { video ->
        viewModel.playNext(video)
        scope.launch { snackbarHostState.showSnackbar("Plays next") }
    }
    val actions = SongActions(
        goToRadio = { video ->
            viewModel.play(video)
            onOpenPlayer()
        },
        playNext = playNext.takeIf { playingId != null },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CapulloTube") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (uiState.isLoading && uiState.sections.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        } else {
            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = viewModel::load,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                HomeList(
                    uiState = uiState,
                    playingId = playingId,
                    onRetry = viewModel::load,
                    actions = actions,
                    onPlayAlbum = { video ->
                        viewModel.playAlbum(video)
                        onOpenPlayer()
                    },
                    onResume = { card ->
                        viewModel.resume(card)
                        onOpenPlayer()
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeList(
    uiState: HomeUiState,
    playingId: String?,
    onRetry: () -> Unit,
    actions: SongActions,
    onPlayAlbum: (YtVideo) -> Unit,
    onResume: (ContinueCard) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        uiState.error?.let { error ->
            item(key = "error") { ErrorBanner(error, onRetry = onRetry) }
        }
        uiState.sections.forEach { section ->
            item(key = "${section.key}-header") { SectionHeader(section.title()) }
            item(key = "${section.key}-row") {
                when (section) {
                    is HomeSection.Continue -> ContinueRow(section.stations, onResume)
                    is HomeSection.ListenAgain ->
                        CardRow(section.cards, playingId, actions, section.albumIds, onPlayAlbum)
                    is HomeSection.BecauseLiked -> CardRow(section.cards, playingId, actions)
                    is HomeSection.FullAlbums -> CardRow(
                        section.cards,
                        playingId,
                        actions,
                        section.cards.mapTo(HashSet()) { it.videoId },
                        onPlayAlbum,
                    )
                    is HomeSection.Trending -> CardRow(section.cards, playingId, actions)
                }
            }
        }
    }
}

private fun HomeSection.title(): String = when (this) {
    is HomeSection.Continue -> "Continue"
    is HomeSection.ListenAgain -> "Listen again"
    is HomeSection.BecauseLiked -> "More like ${TitleCleaner.clean(seed.title, seed.uploader)}"
    is HomeSection.FullAlbums -> "Full albums"
    is HomeSection.Trending -> "Trending in ${countryName(region)}"
}

@Composable
private fun CardRow(
    cards: List<YtVideo>,
    playingId: String?,
    actions: SongActions,
    albumIds: Set<String> = emptySet(),
    onPlayAlbum: (YtVideo) -> Unit = {},
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.HOME_CARD_SPACING),
    ) {
        items(cards, key = { it.videoId }) { video ->
            if (video.videoId in albumIds) {
                AlbumCard(video, isPlaying = video.videoId == playingId, onPlay = onPlayAlbum)
            } else {
                MusicCard(
                    video = video,
                    isPlaying = video.videoId == playingId,
                    actions = actions,
                )
            }
        }
    }
}

@Composable
private fun ContinueRow(stations: List<ContinueCard>, onResume: (ContinueCard) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.HOME_CARD_SPACING),
    ) {
        items(stations, key = { it.stationId }) { card ->
            Column(
                Modifier
                    .width(Dimens.HOME_CARD_WIDTH)
                    .clip(RoundedCornerShape(Dimens.HOME_CARD_CORNER))
                    .clickable(onClickLabel = "Continue") { onResume(card) },
            ) {
                CardArt(card.video.thumbnailUrl, isPlaying = false)
                val title = UpNextText.sourceTitle(card.source)
                Text(
                    "${title.word} · ${title.name}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    UpNextText.clean(card.video),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    card.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun AlbumCard(video: YtVideo, isPlaying: Boolean, onPlay: (YtVideo) -> Unit) {
    Box(
        Modifier
            .width(Dimens.HOME_CARD_WIDTH)
            .clip(RoundedCornerShape(Dimens.HOME_CARD_CORNER))
            .clickable(onClickLabel = "Play album") { onPlay(video) },
    ) { MusicCardContent(video, isPlaying) }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = Dimens.HOME_SECTION_HEADER_TOP,
            bottom = Dimens.HOME_SECTION_HEADER_BOTTOM,
        ),
    )
}

@Composable
private fun MusicCard(video: YtVideo, isPlaying: Boolean, actions: SongActions) {
    SongActionsBox(
        video = video,
        actions = actions,
        modifier = Modifier
            .width(Dimens.HOME_CARD_WIDTH)
            .clip(RoundedCornerShape(Dimens.HOME_CARD_CORNER))
            .semantics { contentDescription = "music_card" },
    ) { MusicCardContent(video, isPlaying) }
}

@Composable
private fun MusicCardContent(video: YtVideo, isPlaying: Boolean) {
    Column {
        CardArt(video.thumbnailUrl, isPlaying)
        Text(
            TitleCleaner.clean(video.title, video.uploader),
            style = MaterialTheme.typography.titleSmall,
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
        viewsLine(video)?.let { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CardArt(thumbnailUrl: String?, isPlaying: Boolean) {
    Box(
        Modifier
            .size(Dimens.HOME_CARD_WIDTH)
            .clip(RoundedCornerShape(Dimens.HOME_CARD_CORNER))
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
        thumbnailUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
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
