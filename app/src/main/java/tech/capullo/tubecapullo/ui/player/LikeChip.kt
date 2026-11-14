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

internal const val LIKE_CHIP_MS = 2_500L

@Stable
class LikeChipState internal constructor() {
    var text: String by mutableStateOf("")
        private set

    var visible: Boolean by mutableStateOf(false)
        private set

    var rollOver: Boolean by mutableStateOf(false)
        private set

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
                contentDescription = null,
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
