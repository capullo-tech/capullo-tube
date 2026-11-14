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
                is LinkEffect.Snackbar -> launch {
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

@Composable
internal fun LinkProgress() {
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

@Composable
internal fun LinkChoiceDialog(choice: LinkChoice, onPick: (LinkPick) -> Unit) {
    val video = choice.video
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
        Icon(icon, contentDescription = null)
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}
