package tech.capullo.tubecapullo

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import tech.capullo.tubecapullo.data.history.PlayHistoryRetention
import javax.inject.Inject

@HiltAndroidApp
class MainApplication : Application() {
    @Inject lateinit var historyRetention: PlayHistoryRetention

    override fun onCreate() {
        super.onCreate()
        historyRetention.start()
    }
}
