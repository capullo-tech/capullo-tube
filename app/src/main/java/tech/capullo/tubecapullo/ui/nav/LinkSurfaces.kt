package tech.capullo.tubecapullo.ui.nav

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.links.LinkChoice
import tech.capullo.tubecapullo.links.LinkEffect
import tech.capullo.tubecapullo.links.LinkPick
import tech.capullo.tubecapullo.links.LinksViewModel
import tech.capullo.tubecapullo.util.TitleCleaner

/**
 * Runs [links]' one-shot effects (navigation, snackbars, toasts) and shows its ask dialog. Hosted
 * by TubeApp, so a message lands on whichever screen is current; a cold start from a share
 * still gets it, because the effects wait in the ViewModel's channel until this collects.
 */
@Composable
internal fun LinkEffects(
    links: LinksViewModel,
    navigator: TubeNavigator,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    LaunchedEffect(links, navigator) {
        links.effects.collect { effect ->
            when (effect) {
                is LinkEffect.Open -> navigator.open(effect.route)
                // Launched: showSnackbar suspends until the snackbar goes, and the next effect
                // (a navigation) must not wait for that.
                is LinkEffect.Snackbar -> launch {
                    // A newer message replaces the one showing rather than queueing behind it.
                    snackbarHostState.currentSnackbarData?.dismiss()
                    snackbarHostState.showSnackbar(effect.text)
                }
                is LinkEffect.Toast ->
                    Toast.makeText(context, effect.text, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val choice by links.choice.collectAsStateWithLifecycle()
    choice?.let { LinkChoiceDialog(it, onPick = links::choose) }
}

/**
 * Shown while an incoming link's video loads (~1 s): without it a share looks ignored
 * (CONTRIBUTING §4). Snackbar-shaped, at the snackbar's place, so the result message that follows
 * takes over the same spot.
 */
@Composable
internal fun LinkProgress() {
    // 12 dp: the margin SnackbarHost's own snackbars get, so the two line up.
    Snackbar(Modifier.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.inversePrimary,
                strokeWidth = 2.dp,
            )
            Text("Opening YouTube link…", modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/**
 * Settings > "Ask what to do with shared links": the choices for one shared or opened video.
 * Play next is absent when nothing plays (there is no queue to pin it into), not disabled
 * (CONTRIBUTING §3). Dismissing the dialog is Cancel.
 */
@Composable
internal fun LinkChoiceDialog(choice: LinkChoice, onPick: (LinkPick) -> Unit) {
    val video = choice.video
    // Display only: the raw title stays the record everywhere else.
    val title = remember(video.title, video.uploader) {
        TitleCleaner.clean(video.title, video.uploader)
    }
    AlertDialog(
        onDismissRequest = { onPick(LinkPick.CANCEL) },
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                video.uploader?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                PickRow(Icons.Default.PlayArrow, "Play now") { onPick(LinkPick.PLAY_NOW) }
                if (choice.canPlayNext) {
                    PickRow(Icons.AutoMirrored.Filled.QueueMusic, "Play next") {
                        onPick(LinkPick.PLAY_NEXT)
                    }
                }
                PickRow(Icons.Default.FavoriteBorder, "Like") { onPick(LinkPick.LIKE) }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(LinkPick.CANCEL) }) { Text("Cancel") } },
    )
}

@Composable
private fun PickRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        // The label names the action; the icon would only repeat it to a screen reader.
        Icon(icon, contentDescription = null)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
