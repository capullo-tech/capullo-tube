package tech.capullo.tubecapullo.ui.debug

import android.util.Log
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import tech.capullo.tubecapullo.ui.nav.Route
import javax.inject.Inject

class DebugCommands @Inject constructor(
    private val engine: StationEngine,
    private val player: PlaybackCoordinator,
) {
    fun run(name: String): Route? {
        Log.i(TAG, "dbg $name")
        when (name) {
            "station" -> return Route.StationDebug
            "player" -> return Route.Player
            "dump" -> dumpToLog()
            "like" -> withCurrent(name) { engine.toggleHeart(it) }
            "skip" -> player.next()
            "liked" -> player.start(PlaySource.Liked(shuffle = false))
            "likedradio" -> player.start(PlaySource.LikedRadio)
            else -> Log.w(TAG, "Unknown dbg '$name'; valid: ${NAMES.joinToString(", ")}")
        }
        return null
    }

    private fun dumpToLog() {
        val dump = StringBuilder()
        engine.dump(dump)
        dump.lineSequence().forEach { Log.i(TAG, it) }
    }

    private inline fun withCurrent(name: String, action: (YtVideo) -> Unit) {
        val current = engine.state.value.current
        if (current == null) Log.w(TAG, "dbg $name: nothing is playing") else action(current)
    }

    companion object {
        const val EXTRA = "dbg"
        private const val TAG = "TubeStation"
        private val NAMES =
            listOf("station", "player", "dump", "like", "skip", "liked", "likedradio")
    }
}
