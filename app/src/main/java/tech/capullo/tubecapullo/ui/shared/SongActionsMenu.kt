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

/**
 * What a long-press on a song card or row offers, each action taking the item's video. Go to
 * radio is also the item's tap. [playNext] is null while nothing plays (no station to pin into),
 * which hides it.
 */
class SongActions(
    val goToRadio: (YtVideo) -> Unit,
    val playNext: ((YtVideo) -> Unit)?,
)

/**
 * A song card or row in a list (Home, Search, Library): a tap is Go to radio, the app-wide track
 * tap; a long-press opens [SongActionsMenu] anchored to the item. [modifier] shapes the clickable
 * area, so a card's clip also clips its ripple.
 */
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

/**
 * A song's own actions as a menu of their own (the list long-press, the Player's title, an Up next
 * row's overflow), worded once for the whole app: Play next pins the song after the playing
 * track, Go to radio starts a song radio from it. A null action is hidden, not disabled
 * (CONTRIBUTING §3): each caller knows which ones change something. A pick closes the menu
 * ([onDismiss]) before it acts.
 */
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

/** The long-press label of a song item, and the click label of the Player's title. */
const val SONG_OPTIONS = "Song options"

private const val PLAY_NEXT = "Play next"
private const val GO_TO_RADIO = "Go to radio"
