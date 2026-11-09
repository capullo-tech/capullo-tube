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
                // Two screens, so a flag instead of a nav library: rememberSaveable survives rotation.
                var showPlayer by rememberSaveable { mutableStateOf(false) }
                if (showPlayer) {
                    PlayerScreen(onBack = { showPlayer = false })
                } else {
                    SearchScreen(onOpenPlayer = { showPlayer = true })
                }
            }
        }
    }
}
