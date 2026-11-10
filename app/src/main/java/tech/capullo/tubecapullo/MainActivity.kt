package tech.capullo.tubecapullo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import tech.capullo.tubecapullo.ui.home.HomeScreen
import tech.capullo.tubecapullo.ui.player.PlayerScreen
import tech.capullo.tubecapullo.ui.search.SearchScreen
import tech.capullo.tubecapullo.ui.theme.TubeCapulloTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Result intentionally unhandled: a denial only hides the playback notification's controls -
    // the foreground service itself is exempt from the permission and keeps running.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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
        setContent {
            TubeCapulloTheme {
                // Three screens (home, search, player), still flag pairs rather than a nav
                // library: rememberSaveable survives rotation, and no screen needs back-stack
                // arguments. Revisit for Navigation3 (CONTRIBUTING §2) when screens need args.
                var showSearch by rememberSaveable { mutableStateOf(false) }
                var showPlayer by rememberSaveable { mutableStateOf(false) }
                when {
                    showPlayer -> PlayerScreen(onBack = { showPlayer = false })
                    showSearch -> SearchScreen(
                        onOpenPlayer = { showPlayer = true },
                        onBack = { showSearch = false },
                    )
                    else -> HomeScreen(
                        onOpenSearch = { showSearch = true },
                        onOpenPlayer = { showPlayer = true },
                    )
                }
            }
        }
    }
}
