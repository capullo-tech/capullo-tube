package tech.capullo.tubecapullo.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
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
}

@Composable
fun MultiroomSheet(onDismiss: () -> Unit, viewModel: MultiroomViewModel = hiltViewModel()) {
    val state by viewModel.snapcastState.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
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

// The UI library does not depend on TunnelManager, so each app bridges the two state models.
private fun TunnelManager.TunnelState.toPublicLinkState(): PublicLinkState = when (this) {
    is TunnelManager.TunnelState.Active -> PublicLinkState.Active(publicUrl)
    TunnelManager.TunnelState.Starting -> PublicLinkState.Starting
    is TunnelManager.TunnelState.Error -> PublicLinkState.Error(message)
    TunnelManager.TunnelState.Off -> PublicLinkState.Off
}
