package tech.capullo.tubecapullo

import android.app.Application
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.history.PlayHistoryRetention
import tech.capullo.tubecapullo.data.settings.AppSettingsLoader
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application() {
    // Injecting the loader starts the read of the settings file.
    @Inject lateinit var settings: AppSettingsLoader

    @Inject lateinit var historyRetention: Lazy<PlayHistoryRetention>

    @Inject lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // PlayHistoryRetention and what it depends on need the settings: build them once the
        // file is read, so that the main thread does not wait for it here.
        scope.launch(Dispatchers.Main) {
            settings.await()
            historyRetention.get().start()
        }
    }
}
