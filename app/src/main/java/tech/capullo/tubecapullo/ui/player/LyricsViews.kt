package tech.capullo.tubecapullo.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import tech.capullo.tubecapullo.data.lyrics.LyricLine
import tech.capullo.tubecapullo.data.lyrics.Lyrics
import tech.capullo.tubecapullo.data.lyrics.LyricsSource
import tech.capullo.tubecapullo.data.lyrics.LyricsTiming
import tech.capullo.tubecapullo.ui.shared.PlayerDrawer
import tech.capullo.tubecapullo.ui.theme.Dimens
import kotlin.math.abs

private const val PAUSE_MARK = "♪"

private const val LINES_ABOVE_CURRENT = 2

@Composable
internal fun LyricsCard(
    lyrics: Lyrics?,
    positionState: StateFlow<Long>,
    offsetMs: Long,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Show lyrics", onClick = onOpen)
            .swipeUp(onOpen),
    ) {
        when {
            lyrics == null -> CardLines("Looking for lyrics…", "", currentFirst = false)
            lyrics.synced -> {
                val index by currentLineIndex(lyrics, positionState, offsetMs)
                CardLines(
                    lyrics.lines.getOrNull(index)?.shownText() ?: PAUSE_MARK,
                    lyrics.lines.getOrNull(index + 1)?.shownText().orEmpty(),
                )
            }
            else -> {
                val first = lyrics.lines.filter { it.text.isNotEmpty() }.take(2)
                CardLines(first.getOrNull(0)?.text.orEmpty(), first.getOrNull(1)?.text.orEmpty())
            }
        }
    }
}

@Composable
internal fun LyricsCardSpace(modifier: Modifier = Modifier) {
    CardLines("", "", modifier = modifier)
}

@Composable
private fun CardLines(first: String, second: String, modifier: Modifier = Modifier, currentFirst: Boolean = true) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.PLAYER_LYRICS_CARD_HEIGHT)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        CardLine(first, current = currentFirst)
        CardLine(second, current = false)
    }
}

@Composable
internal fun LyricsTab(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Show lyrics", onClick = onOpen)
            .swipeUp(onOpen),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        Box(
            Modifier
                .navigationBarsPadding()
                .minimumInteractiveComponentSize()
                .height(Dimens.PLAYER_LYRICS_TAB_HEIGHT)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("Lyrics", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CardLine(text: String, current: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        color = if (current) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun LyricsSheet(
    lyrics: Lyrics?,
    songTitle: String,
    positionState: StateFlow<Long>,
    offsetMs: Long,
    songOffsetMs: Long,
    onSeek: (Long) -> Unit,
    onNudge: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val current by currentLineIndex(lyrics, positionState, offsetMs)

    val listState = remember(lyrics) { LazyListState() }
    var userMoved by remember(lyrics) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.filterIsInstance<DragInteraction.Start>().first()
        userMoved = true
    }
    LaunchedEffect(listState, current) {
        if (!userMoved && current >= 0) {
            listState.animateScrollToItem((current - LINES_ABOVE_CURRENT).coerceAtLeast(0))
        }
    }
    PlayerDrawer(onDismiss = onDismiss, showHandle = false, twoSteps = false) {
        Column {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Lyrics", style = MaterialTheme.typography.titleLarge)
                    Text(
                        songTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        lyrics?.let(::sourceLabel) ?: "Looking for lyrics…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (lyrics?.synced == true) SongNudge(songOffsetMs, onNudge)
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(lyrics?.lines.orEmpty()) { index, line ->
                    SheetLine(
                        line,
                        isCurrent = index == current,
                        onSeek = { time -> onSeek(LyricsTiming.seekTarget(time, offsetMs)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SongNudge(songOffsetMs: Long, onNudge: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onNudge(-LyricsTiming.SONG_NUDGE_STEP_MS) }) {
            Icon(Icons.Default.Remove, contentDescription = "Lyrics earlier for this song")
        }
        Text(nudgeLabel(songOffsetMs), style = MaterialTheme.typography.labelLarge)
        IconButton(onClick = { onNudge(LyricsTiming.SONG_NUDGE_STEP_MS) }) {
            Icon(Icons.Default.Add, contentDescription = "Lyrics later for this song")
        }
    }
}

@Composable
private fun SheetLine(line: LyricLine, isCurrent: Boolean, onSeek: (Long) -> Unit) {
    val time = line.timeMs
    Text(
        line.shownText(),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (time != null) {
                    Modifier
                        .clickable(onClickLabel = "Play from here") { onSeek(time) }
                        .minimumInteractiveComponentSize()
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun currentLineIndex(lyrics: Lyrics?, positionState: StateFlow<Long>, offsetMs: Long): State<Int> {
    val position = positionState.collectAsStateWithLifecycle()
    return remember(lyrics, offsetMs) {
        derivedStateOf { lyrics?.let { LyricsTiming.lineIndex(it, position.value, offsetMs) } ?: -1 }
    }
}

private fun sourceLabel(lyrics: Lyrics): String {
    val source = when (lyrics.source) {
        LyricsSource.LRCLIB_GET, LyricsSource.LRCLIB_SEARCH -> "Lyrics by LRCLIB"
        LyricsSource.DESCRIPTION -> "From the video description"
    }
    return if (lyrics.synced) source else "$source · not synced"
}

private fun nudgeLabel(offsetMs: Long): String {
    if (offsetMs == 0L) return "0 s"
    val sign = if (offsetMs > 0) "+" else "−"
    val ms = abs(offsetMs)
    return "$sign${ms / 1000}.${ms % 1000 / 100} s"
}

private fun LyricLine.shownText(): String = text.ifEmpty { if (timeMs != null) PAUSE_MARK else "" }

private fun Modifier.swipeUp(onSwipeUp: () -> Unit): Modifier = pointerInput(Unit) {
    val threshold = ART_SWIPE_THRESHOLD.toPx()
    var dy = 0f
    detectVerticalDragGestures(
        onDragStart = { dy = 0f },
        onVerticalDrag = { change, amount ->
            change.consume()
            dy += amount
        },
        onDragEnd = { if (dy <= -threshold) onSwipeUp() },
    )
}
