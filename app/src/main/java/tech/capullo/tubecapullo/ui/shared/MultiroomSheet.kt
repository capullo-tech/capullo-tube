package tech.capullo.tubecapullo.ui.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import tech.capullo.audio.ui.SnapcastControlSheet
import tech.capullo.tubecapullo.snapcast.SnapcastManager
import javax.inject.Inject

@HiltViewModel
class MultiroomViewModel @Inject constructor(
    private val snapcastManager: SnapcastManager,
) : ViewModel() {
    val snapcastState = snapcastManager.state
    val localClientId: String get() = snapcastManager.localClientId

    fun setClientVolume(clientId: String, muted: Boolean, percent: Int) = snapcastManager.adjustClientVolume(clientId, percent, muted)

    fun setClientLatency(clientId: String, latencyMs: Int) = snapcastManager.adjustClientLatency(clientId, latencyMs)
}

@Composable
fun MultiroomSheet(onDismiss: () -> Unit, viewModel: MultiroomViewModel = hiltViewModel()) {
    val state by viewModel.snapcastState.collectAsStateWithLifecycle()
    SnapcastControlSheet(
        groups = state.groups,
        onClientVolumeChange = viewModel::setClientVolume,
        onClientLatencyChange = viewModel::setClientLatency,
        localClientId = viewModel.localClientId,
        httpPort = state.broadcastHttpPort,
        onDismiss = onDismiss,
    )
}
