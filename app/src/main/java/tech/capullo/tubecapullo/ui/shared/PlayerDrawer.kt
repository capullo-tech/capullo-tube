package tech.capullo.tubecapullo.ui.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import tech.capullo.tubecapullo.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerDrawer(
    onDismiss: () -> Unit,
    showHandle: Boolean = true,
    twoSteps: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val maxSheetHeight = (windowHeight - statusBar) * Dimens.PLAYER_DRAWER_MAX_HEIGHT_FRACTION
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !twoSteps),
        modifier = Modifier.padding(horizontal = Dimens.PLAYER_DRAWER_SIDE_MARGIN),
        dragHandle = if (showHandle) ({ BottomSheetDefaults.DragHandle() }) else null,
    ) {
        val handleSpace = if (showHandle) Dimens.PLAYER_DRAWER_HANDLE_SPACE else 0.dp
        val maxContentHeight = maxSheetHeight - handleSpace

        Box(
            Modifier.fillMaxWidth().heightIn(max = maxContentHeight).navigationBarsPadding(),
            content = content,
        )
    }
}
