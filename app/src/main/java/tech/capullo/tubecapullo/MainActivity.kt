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

/**
 * The one activity. Portrait only (manifest, owner decision 2026-11-12); the YouTube link alias
 * targets it, so links open in portrait too. Android 16+ may ignore an orientation lock on large
 * screens (tablets, foldables), and the activity then rotates there: accepted. It is still
 * recreated by other configuration changes (dark mode, font size, window resize) and by process
 * death, so saved state stays saved (rememberSaveable, savedInstanceState).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var debugCommands: DebugCommands

    // Activity scoped, so a link still loading survives a recreate; TubeApp shows its state.
    private val links: LinksViewModel by viewModels()

    // Result intentionally unhandled: a denial only hides the playback notification's controls -
    // the foreground service itself is exempt from the permission and keeps running.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Routes requested by intents (notification tap, dbg), consumed by TubeApp's navigator.
    // Buffered so a launch intent handled before setContent is not lost.
    private val navRequests = Channel<Route>(Channel.UNLIMITED)
    private val navRequestFlow = navRequests.receiveAsFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // API 33+ requires a runtime grant before the media notification can show. Ask up front:
        // the app's whole purpose is background playback, so the notification IS the app surface
        // once the screen is off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // A recreated activity (configuration change, process death) gets the same intent back;
        // its routes are already in the restored back stack, its dbg command already ran and its
        // link was already handled.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            TubeCapulloTheme {
                TubeApp(navRequests = navRequestFlow, links = links)
            }
        }
    }

    // singleTop (manifest): a notification tap, a dbg command, a share or a tapped link while the
    // app is open lands here.
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
        // Recents relaunches a finished task with its original intent: a share must not play or
        // queue its track a second time.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        when (intent.action) {
            // getCharSequence: some apps share styled text, which getStringExtra reads as null.
            Intent.ACTION_SEND -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                links.handle(LinkVia.SHARE, text)
            }
            Intent.ACTION_VIEW -> links.handle(LinkVia.VIEW, intent.dataString)
        }
    }
}
