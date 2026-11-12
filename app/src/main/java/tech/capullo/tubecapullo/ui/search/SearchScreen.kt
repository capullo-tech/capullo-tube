package tech.capullo.tubecapullo.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.youtube.YtCollection
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.ui.shared.CompactListItem
import tech.capullo.tubecapullo.ui.shared.SongActions
import tech.capullo.tubecapullo.ui.shared.SongActionsBox
import tech.capullo.tubecapullo.ui.theme.Dimens
import tech.capullo.tubecapullo.util.TitleCleaner
import tech.capullo.tubecapullo.util.formatDuration
import tech.capullo.tubecapullo.util.viewsLine

/**
 * Search YouTube and tap a row to start a song radio from it. "Radio from results" above the list
 * starts a station from the whole result set instead (the first result plays, the other results
 * follow, then its Mix); when the results are a pasted or shared playlist, "Play playlist" and "Shuffle" play it
 * as itself. A long-press on a song row opens its actions: Play next (only while something
 * plays; there is no station to queue into otherwise) and Go to radio, which also opens the
 * Player.
 *
 * The chips under the field pick what search terms list: Songs (the default), Playlists, Albums
 * or Channels. A playlist, album or channel row plays that list as itself and opens the Player.
 * Changing the chip runs the query again.
 *
 * Deliberately one text field for all three inputs (search terms, a video link, a playlist link):
 * the parsing that tells them apart is already exact (`YouTubeUrls`), so making the user pick a
 * mode first would be asking them to answer a question the app can answer itself. That is also
 * why a link ignores the chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenPlayer: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
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
        // Hidden while nothing plays: there is no station to queue into.
        playNext = playNext.takeIf { playingId != null },
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("CapulloTube") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            FilterChips(uiState.filter, onSelect = viewModel::onFilterChange)

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
                    uiState.collectionKind != null && uiState.collections.isNotEmpty() ->
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(uiState.collections, key = { it.url }) { collection ->
                                CollectionRow(collection) {
                                    viewModel.playCollection(collection)
                                    onOpenPlayer()
                                }
                                HorizontalDivider()
                            }
                        }
                    uiState.results.isEmpty() -> Text(
                        "Search for a song, or paste a video or playlist link",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                    else -> Column(Modifier.fillMaxSize()) {
                        if (uiState.playlist != null) {
                            // A pasted or shared playlist plays as itself, in order or shuffled.
                            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                ResultsButton(
                                    "Play playlist",
                                    Icons.AutoMirrored.Filled.PlaylistPlay,
                                ) {
                                    viewModel.playPlaylist(shuffle = false)
                                    onOpenPlayer()
                                }
                                Spacer(Modifier.width(8.dp))
                                ResultsButton("Shuffle", Icons.Default.Shuffle) {
                                    viewModel.playPlaylist(shuffle = true)
                                    onOpenPlayer()
                                }
                            }
                        } else {
                            ResultsButton(
                                "Radio from results",
                                Icons.Default.Radio,
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                viewModel.playResultsRadio()
                                onOpenPlayer()
                            }
                        }
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(uiState.results, key = { it.videoId }) { video ->
                                ResultRow(
                                    video = video,
                                    isPlaying = video.videoId == playingId,
                                    actions = actions,
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One single-select chip per [SearchFilter], under the search field. The four share the width
 *  equally, so the row never scrolls, and every label stays whole (owner report 2026-11-12:
 *  "Channe" on the 393 dp OPPO).
 *
 *  FilterChip in Material3 1.4.0 has no content padding parameter: it keeps
 *  [CHIP_LABEL_PADDING] on each side of the label (ChipKt's FilterChipPadding, read from the
 *  1.4.0 bytecode). A SegmentedButton row was the other option and is worse here: its content
 *  always reserves the checkmark's 18 dp plus 8 dp spacing beside the label, even with an empty
 *  icon (SegmentedButtonContentMeasurePolicy, same bytecode). So the label is laid out over the
 *  chip's side padding ([overChipPadding]); the chip's outline and corners stay where they are.
 *  Calculated, not measured: on a 360 dp screen each chip is (328 - 3 x 6) / 4 = 77.5 dp, about
 *  75 dp of it for the label inside the outline, against 60 dp before. If a label still does
 *  not fit (large font scale, a wide OEM font), it shrinks step by step down to
 *  [CHIP_LABEL_MIN_SIZE] instead of being cut. No leading checkmark (it would take 18 dp + 8 dp
 *  of the width); the selected chip shows its state through the container color. */
@Composable
private fun FilterChips(selected: SearchFilter, onSelect: (SearchFilter) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Dimens.SEARCH_CHIP_SPACING),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        val style = MaterialTheme.typography.labelMedium
        SearchFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        filter.label,
                        style = style,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = CHIP_LABEL_MIN_SIZE,
                            maxFontSize = style.fontSize,
                            stepSize = CHIP_LABEL_STEP,
                        ),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.overChipPadding().fillMaxWidth(),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Measures the label [CHIP_LABEL_PADDING] wider on each side than the chip offers and centres
 *  it over that padding, which is still inside the chip's outline. */
private fun Modifier.overChipPadding(): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val wider = constraints.maxWidth + (CHIP_LABEL_PADDING * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = wider, maxWidth = wider))
    val width = constraints.maxWidth
    layout(width, placeable.height) { placeable.place((width - placeable.width) / 2, 0) }
}

/** FilterChip's own space on each side of its label, Material3 1.4.0 (ChipKt
 *  HorizontalElementsPadding, not public). Mirrors the library: if a Material3 update changes
 *  it, the label overlaps the outline (larger) or loses width again (smaller). */
private val CHIP_LABEL_PADDING = 8.dp

/** The smallest a chip label shrinks to before it would be cut. GUESS, unmeasured: 10 sp still
 *  reads as a label next to the 12 sp ones; smaller reads as fine print. */
private val CHIP_LABEL_MIN_SIZE = 10.sp

/** Shrink step of a chip label that does not fit. Self-evident granularity, not tuned: fine
 *  enough that a label shrinks only as far as it must. */
private val CHIP_LABEL_STEP = 0.5.sp

@Composable
private fun ResultsButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = modifier,
    ) {
        // Decorative: the button's text label carries the meaning.
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(label, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

@Composable
private fun ResultRow(video: YtVideo, isPlaying: Boolean, actions: SongActions) {
    SongActionsBox(video, actions) { ResultRowContent(video, isPlaying) }
}

@Composable
private fun ResultRowContent(video: YtVideo, isPlaying: Boolean) {
    CompactListItem(
        leadingContent = {
            // Icon only: song rows kept their look when the collection filters arrived (owner
            // call 2026-11-11, "Songs keeps today's result rows"); collection rows show art.
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
        headlineContent = {
            Text(
                TitleCleaner.clean(video.title, video.uploader),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(video.uploader, formatDuration(video.durationSeconds))
                        .joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                viewsLine(video)?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        },
        trailingContent = if (isPlaying) {
            {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = "Playing",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
    )
}

/** A playlist, album or channel: art, name, then owner and size when known. A tap plays it. */
@Composable
private fun CollectionRow(collection: YtCollection, onPlay: () -> Unit) {
    val details = remember(collection) { collectionDetails(collection) }
    CompactListItem(
        leadingContent = { CollectionArt(collection.thumbnailUrl) },
        headlineContent = {
            Text(collection.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = details?.let {
            { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        modifier = Modifier.clickable(onClickLabel = "Play", onClick = onPlay),
    )
}

/** Placeholder under the Coil image, so a slow load shows the placeholder, not a flicker (the
 *  Library and Up next rows' pattern). */
@Composable
private fun CollectionArt(url: String?) {
    Box(
        Modifier
            .size(Dimens.LIST_ART_SIZE)
            .clip(RoundedCornerShape(Dimens.LIST_ART_CORNER))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.PlaylistPlay,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center).size(Dimens.LIST_ART_NOTE_SIZE),
        )
        url?.let {
            AsyncImage(
                model = it,
                contentDescription = null, // decorative; the name text carries the meaning
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
