package tech.capullo.tubecapullo.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import tech.capullo.tubecapullo.data.settings.AppSettings

/**
 * Says once that the settings file was corrupt and has been replaced by the defaults. Without it
 * a reset looks like the app forgetting what the user set. The notice stays in the file until the
 * user closes this dialog, so it also covers a reset that happened with no screen open.
 */
@Composable
fun StoreResetDialog(settings: AppSettings) {
    val notice by settings.resetNotice.collectAsState()
    if (!notice) return
    AlertDialog(
        onDismissRequest = settings::acknowledgeReset,
        title = { Text("Settings were reset") },
        text = {
            Text(
                "CapulloTube could not read its settings file, because the file was damaged. " +
                    "All settings are at their defaults again, and a personal Last.fm key is gone. " +
                    "Liked songs, history and playlists are not affected.",
            )
        },
        confirmButton = { TextButton(onClick = settings::acknowledgeReset) { Text("OK") } },
    )
}
