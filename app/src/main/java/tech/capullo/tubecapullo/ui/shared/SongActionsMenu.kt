package tech.capullo.tubecapullo.ui.shared

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import tech.capullo.tubecapullo.data.youtube.YtVideo

class SongActions(
    val goToRadio: (YtVideo) -> Unit,
    val playNext: ((YtVideo) -> Unit)?,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongActionsBox(
    video: YtVideo,
    actions: SongActions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(
        modifier.combinedClickable(
            onClickLabel = GO_TO_RADIO,
            onLongClickLabel = SONG_OPTIONS,
            onLongClick = { menuOpen = true },
            onClick = { actions.goToRadio(video) },
        ),
    ) {
        content()
        SongActionsMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onPlayNext = actions.playNext?.let { playNext -> { playNext(video) } },
            onGoToRadio = { actions.goToRadio(video) },
        )
    }
}

@Composable
fun SongActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: (() -> Unit)?,
    onGoToRadio: (() -> Unit)?,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        listOfNotNull(
            onPlayNext?.let { PLAY_NEXT to it },
            onGoToRadio?.let { GO_TO_RADIO to it },
        ).forEach { (label, action) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    onDismiss()
                    action()
                },
            )
        }
    }
}

const val SONG_OPTIONS = "Song options"

private const val PLAY_NEXT = "Play next"
private const val GO_TO_RADIO = "Go to radio"
