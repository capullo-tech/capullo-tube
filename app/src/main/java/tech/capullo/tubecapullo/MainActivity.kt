package tech.capullo.tubecapullo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import tech.capullo.tubecapullo.links.LinkVia
import tech.capullo.tubecapullo.links.LinksViewModel
import tech.capullo.tubecapullo.player.PlaybackService
import tech.capullo.tubecapullo.ui.debug.DebugCommands
import tech.capullo.tubecapullo.ui.nav.Route
import tech.capullo.tubecapullo.ui.nav.TubeApp
import tech.capullo.tubecapullo.ui.theme.TubeCapulloTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var debugCommands: DebugCommands

    private val links: LinksViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val navRequests = Channel<Route>(Channel.UNLIMITED)
    private val navRequestFlow = navRequests.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            TubeCapulloTheme {
                TubeApp(navRequests = navRequestFlow, links = links)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false)) {
            navRequests.trySend(Route.Player)
        }
        intent.getStringExtra(DebugCommands.EXTRA)
            ?.let(debugCommands::run)
            ?.let { navRequests.trySend(it) }
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                links.handle(LinkVia.SHARE, text)
            }
            Intent.ACTION_VIEW -> links.handle(LinkVia.VIEW, intent.dataString)
        }
    }
}
