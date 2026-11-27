package tech.capullo.tubecapullo

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.settings.AppSettingsLoader
import tech.capullo.tubecapullo.links.LinkVia
import tech.capullo.tubecapullo.links.LinksViewModel
import tech.capullo.tubecapullo.player.PlaybackService
import tech.capullo.tubecapullo.ui.debug.DebugCommands
import tech.capullo.tubecapullo.ui.nav.Route
import tech.capullo.tubecapullo.ui.nav.TubeApp
import tech.capullo.tubecapullo.ui.settings.StoreResetDialog
import tech.capullo.tubecapullo.ui.theme.TubeCapulloTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Lazy: building it builds the station engine, which needs the settings.
    @Inject lateinit var debugCommands: Lazy<DebugCommands>

    @Inject lateinit var settingsLoader: AppSettingsLoader

    private val links: LinksViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val navRequests = Channel<Route>(Channel.UNLIMITED)
    private val navRequestFlow = navRequests.receiveAsFlow()

    /** Intents that arrive before the screen is shown; [show] handles them after the launch intent. */
    private var pendingIntents: MutableList<Intent>? = mutableListOf()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val launchIntent = intent.takeIf { savedInstanceState == null }
        if (settingsLoader.isLoaded) {
            show(settingsLoader.get(), launchIntent)
            return
        }
        // The screen and the link handling need the settings. Until the file is read, the first
        // frame is held back so that the starting window stays. Meanwhile the main thread sets up
        // Compose with an empty theme, work that would otherwise come after the read.
        val content = findViewById<View>(android.R.id.content)
        val holdFirstFrame = ViewTreeObserver.OnPreDrawListener { false }
        content.viewTreeObserver.addOnPreDrawListener(holdFirstFrame)
        setContent { TubeCapulloTheme {} }
        lifecycleScope.launch {
            val settings = settingsLoader.await()
            content.viewTreeObserver.removeOnPreDrawListener(holdFirstFrame)
            show(settings, launchIntent)
        }
    }

    private fun show(settings: AppSettings, launchIntent: Intent?) {
        launchIntent?.let(::handleIntent)
        pendingIntents?.forEach(::handleIntent)
        pendingIntents = null
        setContent {
            TubeCapulloTheme {
                TubeApp(navRequests = navRequestFlow, links = links)
                StoreResetDialog(settings)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntents?.add(intent) ?: handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false)) {
            navRequests.trySend(Route.Player)
        }
        // Debuggable builds only: the activity is exported, and `publiclink on` would let any app
        // put this phone's broadcast on the internet.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent.getStringExtra(DebugCommands.EXTRA)
                ?.let { debugCommands.get().run(it, intent.getStringExtra(DebugCommands.EXTRA_VALUE)) }
                ?.let { navRequests.trySend(it) }
        }
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
