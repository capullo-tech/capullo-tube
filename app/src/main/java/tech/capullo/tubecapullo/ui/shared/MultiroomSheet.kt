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

/** Snapcast listener controls behind the Multiroom sheet: a pass-through to [SnapcastManager]. */
@HiltViewModel
class MultiroomViewModel @Inject constructor(
    private val snapcastManager: SnapcastManager,
) : ViewModel() {
    val snapcastState = snapcastManager.state
    val localClientId: String get() = snapcastManager.localClientId

    fun setClientVolume(clientId: String, muted: Boolean, percent: Int) = snapcastManager.adjustClientVolume(clientId, percent, muted)

    fun setClientLatency(clientId: String, latencyMs: Int) = snapcastManager.adjustClientLatency(clientId, latencyMs)
}

/**
 * The Multiroom sheet: the shared capullo-audio-ui SnapcastControlSheet (same sheet telecloud
 * uses), opened from the Player's multiroom icon. The sheet's own QR button shows the
 * web-player URL, its QR code and a Share action (ListenQrDialog at the pinned 92d1b63).
 */
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
