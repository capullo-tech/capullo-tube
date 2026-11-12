package tech.capullo.tubecapullo.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import tech.capullo.tubecapullo.station.LikeEffect
import tech.capullo.tubecapullo.station.RollOver
import tech.capullo.tubecapullo.ui.upnext.UpNextText

/**
 * How long the like chip ("Added 3 from X's Mix") stays, in ms. Long enough to read a short
 * sentence once, short enough not to sit over the art. GUESS: not measured with listeners;
 * Material's short snackbar (4 s) felt long for a confirmation the heart already shows.
 */
internal const val LIKE_CHIP_MS = 2_500L

/** The transient station chip: what a like did to the queue ([UpNextText.likeChip]), or that
 *  a playlist, channel or album ended and the station goes on as a radio
 *  ([UpNextText.rollChip]). It hides itself after [LIKE_CHIP_MS]; a second chip restarts the
 *  timer. */
@Stable
class LikeChipState internal constructor() {
    /** The last chip text; kept after [visible] drops so the fade-out still shows it. */
    var text: String by mutableStateOf("")
        private set

    var visible: Boolean by mutableStateOf(false)
        private set

    /** The chip says a list or album rolled into a radio (a radio icon), not a like (a heart). */
    var rollOver: Boolean by mutableStateOf(false)
        private set

    /** Bumped per [show], so the hide timer restarts even when the text repeats. */
    internal var shown by mutableIntStateOf(0)
        private set

    internal fun show(message: String, rolled: Boolean) {
        text = message
        rollOver = rolled
        visible = true
        shown++
    }

    internal fun hide() {
        visible = false
    }
}

/**
 * The chip's state, fed by [effects] (the engine's like effects) and [rollOvers] (the engine's
 * roll-overs) only while the caller is composed. The Player and the mini-bar each hold one, and
 * the mini-bar is gone while the Player is open, so one event shows one chip. An effect lands
 * once the liked song's Mix does, so the chip may come up on the other surface than the heart
 * that was tapped.
 */
@Composable
fun rememberLikeChipState(effects: Flow<LikeEffect>, rollOvers: Flow<RollOver>): LikeChipState {
    val state = remember { LikeChipState() }
    LaunchedEffect(effects) { effects.collect { state.show(UpNextText.likeChip(it), rolled = false) } }
    LaunchedEffect(rollOvers) { rollOvers.collect { state.show(UpNextText.rollChip(it), rolled = true) } }
    LaunchedEffect(state.shown) {
        if (!state.visible) return@LaunchedEffect
        delay(LIKE_CHIP_MS)
        state.hide()
    }
    return state
}

/** The chip in place; fades in and out with [state]. */
@Composable
fun LikeChip(state: LikeChipState, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        ChipBody(state.text, state.rollOver)
    }
}

/** The chip floating just above its parent (the mini-bar), so showing it never moves the
 *  layout around it. */
@Composable
fun LikeChipAbove(state: LikeChipState) {
    if (!state.visible) return
    Popup(popupPositionProvider = AboveParentCentered) { ChipBody(state.text, state.rollOver) }
}

@Composable
private fun ChipBody(text: String, rollOver: Boolean) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 2.dp,
        modifier = Modifier.padding(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                if (rollOver) Icons.Default.Radio else Icons.Default.Favorite,
                contentDescription = null, // decorative; the text says it
                modifier = Modifier.padding(end = 4.dp).size(16.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private object AboveParentCentered : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2,
        y = anchorBounds.top - popupContentSize.height,
    )
}
