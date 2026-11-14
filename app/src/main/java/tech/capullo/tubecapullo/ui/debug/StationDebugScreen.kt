package tech.capullo.tubecapullo.ui.debug

import android.content.ClipData
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.station.StationEngine
import javax.inject.Inject

private const val DEBUG_REFRESH_MS = 1_000L

@HiltViewModel
class StationDebugViewModel @Inject constructor(
    private val engine: StationEngine,
) : ViewModel() {
    val dump: StateFlow<String> = flow {
        while (true) {
            emit(render())
            delay(DEBUG_REFRESH_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), render())

    private fun render(): String = StringBuilder().also { engine.dump(it) }.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationDebugScreen(onBack: () -> Unit, viewModel: StationDebugViewModel = hiltViewModel()) {
    val dump by viewModel.dump.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Station debug") },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(
                                    ClipEntry(ClipData.newPlainText("Station dump", dump)),
                                )
                            }
                        },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy dump")
                    }
                },
            )
        },
    ) { padding ->
        SelectionContainer(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                dump,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
