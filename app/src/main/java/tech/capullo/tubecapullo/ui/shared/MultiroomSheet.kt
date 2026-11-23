package tech.capullo.tubecapullo.ui.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import tech.capullo.audio.snapcast.SnapclientProcess
import tech.capullo.audio.tunnel.TunnelManager
import tech.capullo.audio.ui.PublicLinkState
import tech.capullo.audio.ui.SnapcastControlSheet
import tech.capullo.tubecapullo.snapcast.SnapcastManager
import javax.inject.Inject

@HiltViewModel
class MultiroomViewModel @Inject constructor(
    private val snapcastManager: SnapcastManager,
    tunnelManager: TunnelManager,
) : ViewModel() {
    val snapcastState = snapcastManager.state
    val tunnelState = tunnelManager.state
    val localClientId: String get() = snapcastManager.localClientId

    fun setClientVolume(clientId: String, muted: Boolean, percent: Int) = snapcastManager.adjustClientVolume(clientId, percent, muted)

    fun setClientLatency(clientId: String, latencyMs: Int) = snapcastManager.adjustClientLatency(clientId, latencyMs)

    fun leave() = snapcastManager.leave()
}

@Composable
fun MultiroomSheet(onDismiss: () -> Unit, viewModel: MultiroomViewModel = hiltViewModel()) {
    val state by viewModel.snapcastState.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
    val listeningTo = state.listeningTo
    if (listeningTo != null) {
        ListeningSheet(
            host = listeningTo,
            connection = state.clientConnection,
            onLeave = {
                viewModel.leave()
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    } else {
        SnapcastControlSheet(
            groups = state.groups,
            onClientVolumeChange = viewModel::setClientVolume,
            onClientLatencyChange = viewModel::setClientLatency,
            localClientId = viewModel.localClientId,
            httpPort = state.broadcastHttpPort,
            publicLink = tunnelState.toPublicLinkState(),
            onDismiss = onDismiss,
        )
    }
}

/** This device plays a station it joined from a QR, not its own: say which, and offer Leave. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListeningSheet(
    host: String,
    connection: SnapclientProcess.ConnectionState,
    onLeave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Same scrim as SnapcastControlSheet: TubeApp blurs the screen behind either.
        scrimColor = Color.Black.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Listening to $host",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (connection) {
                    SnapclientProcess.ConnectionState.STARTING -> "Connecting…"
                    SnapclientProcess.ConnectionState.CONNECTED -> "Connected. Playing here in sync with the station."
                    SnapclientProcess.ConnectionState.ERROR -> "Can't reach the station."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onLeave, modifier = Modifier.padding(top = 8.dp)) {
                Text("Leave")
            }
        }
    }
}

// The UI library does not depend on TunnelManager, so each app bridges the two state models.
private fun TunnelManager.TunnelState.toPublicLinkState(): PublicLinkState = when (this) {
    is TunnelManager.TunnelState.Active -> PublicLinkState.Active(publicUrl)
    TunnelManager.TunnelState.Starting -> PublicLinkState.Starting
    is TunnelManager.TunnelState.Error -> PublicLinkState.Error(message)
    TunnelManager.TunnelState.Off -> PublicLinkState.Off
}
