package tech.capullo.tubecapullo.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import tech.capullo.tubecapullo.data.lyrics.Lrc
import tech.capullo.tubecapullo.data.lyrics.LyricLine
import tech.capullo.tubecapullo.data.lyrics.Lyrics
import tech.capullo.tubecapullo.data.lyrics.LyricsSource
import tech.capullo.tubecapullo.ui.shared.PlayerDrawer
import tech.capullo.tubecapullo.ui.theme.Dimens

/** Shown for a synced line with no words: an instrumental break, or the time before the first
 *  line. */
private const val PAUSE_MARK = "♪"

/**
 * Lines kept above the current one when the drawer scrolls to it, so the listener sees what
 * was just sung. Judgement, not measured: at half height the drawer shows about 12 lines.
 */
private const val LINES_ABOVE_CURRENT = 2

/**
 * The card at the Player's bottom: the current line and the next one (synced lyrics; the
 * position decides), or the first two lines (plain lyrics). [lyrics] null holds the slot while
 * the next track's lookup runs. A tap or a drag up opens the drawer.
 */
@Composable
internal fun LyricsCard(
    lyrics: Lyrics?,
    positionState: StateFlow<Long>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.PLAYER_LYRICS_CARD_HEIGHT)
            .clickable(onClickLabel = "Show lyrics", onClick = onOpen)
            .swipeUp(onOpen),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            when {
                lyrics == null -> CardLine("Looking for lyrics…", current = false)
                lyrics.synced -> {
                    val index by currentLineIndex(lyrics, positionState)
                    val line = lyrics.lines.getOrNull(index)
                    CardLine(line?.shownText() ?: PAUSE_MARK, current = true)
                    lyrics.lines.getOrNull(index + 1)?.let { next ->
                        CardLine(next.shownText(), current = false)
                    }
                }
                else -> {
                    val first = lyrics.lines.filter { it.text.isNotEmpty() }.take(2)
                    first.forEachIndexed { i, line -> CardLine(line.text, current = i == 0) }
                }
            }
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

/**
 * The lyrics drawer: every line, in a [PlayerDrawer] (the Queue sheet's two steps and height
 * cap). Synced lyrics highlight the current line and scroll to it until the listener drags the
 * list; a tap on a synced line seeks there. The source shows under the title. [lyrics] null:
 * the next track's lookup runs, and the drawer stays open for its lyrics.
 */
@Composable
internal fun LyricsSheet(
    lyrics: Lyrics?,
    songTitle: String,
    positionState: StateFlow<Long>,
    onSeek: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val current by currentLineIndex(lyrics, positionState)
    // Per song: a new song's lines start at the top and follow the song again.
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
    PlayerDrawer(onDismiss = onDismiss) {
        Column {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Lyrics", style = MaterialTheme.typography.titleLarge)
                Text(
                    "$songTitle · ${lyrics?.let(::sourceLabel) ?: "Looking for lyrics…"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(lyrics?.lines.orEmpty()) { index, line ->
                    SheetLine(line, isCurrent = index == current, onSeek = onSeek)
                }
            }
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
                    Modifier.clickable(onClickLabel = "Play from here") { onSeek(time) }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** The index of the synced line playing ([Lrc.currentLineIndex]); -1 for plain or no lyrics.
 *  Derived, so the 500 ms position tick recomposes a reader only when the line changes. */
@Composable
private fun currentLineIndex(lyrics: Lyrics?, positionState: StateFlow<Long>): State<Int> {
    val position = positionState.collectAsStateWithLifecycle()
    return remember(lyrics) {
        derivedStateOf {
            if (lyrics?.synced == true) Lrc.currentLineIndex(lyrics.lines, position.value) else -1
        }
    }
}

/** Where the lyrics came from, and for plain lyrics why no line follows the song. */
private fun sourceLabel(lyrics: Lyrics): String {
    val source = when (lyrics.source) {
        LyricsSource.LRCLIB_GET, LyricsSource.LRCLIB_SEARCH -> "Lyrics by LRCLIB"
        LyricsSource.DESCRIPTION -> "From the video description"
    }
    return if (lyrics.synced) source else "$source · not synced"
}

/** A synced line's words; a pause mark for a line with none. Plain blank lines stay blank. */
private fun LyricLine.shownText(): String = text.ifEmpty { if (timeMs != null) PAUSE_MARK else "" }

/** A drag up over [ART_SWIPE_THRESHOLD] calls [onSwipeUp] once the finger lifts, as the art's
 *  swipe up opens the Queue. */
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
