package tech.capullo.tubecapullo.data.settings

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the settings file on [scope]'s background thread from the moment it is created, which is
 * when the app starts. The first read took 364 ms on the OnePlus 3 (Android 11), and it used to
 * run on the main thread before the first frame. The first screen waits for [await]; [get] is for
 * code that cannot suspend.
 */
@Singleton
class AppSettingsLoader @Inject constructor(
    @ApplicationContext context: Context,
    scope: CoroutineScope,
) {
    private val settings: Deferred<AppSettings> = scope.async {
        val start = SystemClock.elapsedRealtime()
        AppSettings.load(context.appSettingsStore, scope).also {
            Log.d(TAG, "settings read in ${SystemClock.elapsedRealtime() - start} ms")
        }
    }

    val isLoaded: Boolean get() = settings.isCompleted

    suspend fun await(): AppSettings = settings.await()

    /** Blocks the calling thread until the file is read, and logs any wait. */
    fun get(): AppSettings {
        if (settings.isCompleted) return runBlocking { settings.await() }
        val start = SystemClock.elapsedRealtime()
        return runBlocking { settings.await() }.also {
            Log.d(TAG, "${Thread.currentThread().name} waited ${SystemClock.elapsedRealtime() - start} ms for the settings")
        }
    }

    private companion object {
        const val TAG = "Settings"
    }
}
