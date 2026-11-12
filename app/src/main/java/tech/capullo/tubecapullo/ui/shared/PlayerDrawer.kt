package tech.capullo.tubecapullo.ui.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import tech.capullo.tubecapullo.ui.theme.Dimens

/**
 * A drawer over the Player: the Queue sheet and the lyrics drawer. Two steps (owner call
 * 2026-11-12): it opens at half the screen, and a drag up shows the capped full height. It is
 * never full screen: the cap keeps the Player's top in view above the expanded drawer, and the
 * side margins make it float (owner review 2026-11-11; [Dimens]).
 *
 * The cap sits on the CONTENT, not on the sheet: with `heightIn` on the sheet's own modifier,
 * M3 1.4.0 placed the Expanded anchor for the uncapped content, so sliding up parked the capped
 * sheet against the top of the screen with an empty band below it (owner report 2026-11-12;
 * measured on the OPPO: sheet top at 178 px, list bottom at 1813 px, navigation bar at 2268 px).
 * Capping the content keeps the sheet's measured height and its anchors the same thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerDrawer(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val maxSheetHeight = (windowHeight - statusBar) * Dimens.PLAYER_DRAWER_MAX_HEIGHT_FRACTION
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        modifier = Modifier.padding(horizontal = Dimens.PLAYER_DRAWER_SIDE_MARGIN),
    ) {
        val maxContentHeight = maxSheetHeight - Dimens.PLAYER_DRAWER_HANDLE_SPACE
        Box(Modifier.fillMaxWidth().heightIn(max = maxContentHeight), content = content)
    }
}
