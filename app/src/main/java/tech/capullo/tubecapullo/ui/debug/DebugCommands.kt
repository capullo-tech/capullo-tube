package tech.capullo.tubecapullo.ui.debug

import android.util.Log
import tech.capullo.audio.snapcast.JoinUrl
import tech.capullo.audio.tunnel.TunnelManager
import tech.capullo.audio.ui.usefulLocalIps
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.snapcast.SnapcastManager
import tech.capullo.tubecapullo.snapcast.publicJoinUrl
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import tech.capullo.tubecapullo.ui.nav.Route
import javax.inject.Inject

class DebugCommands @Inject constructor(
    private val engine: StationEngine,
    private val player: PlaybackCoordinator,
    private val snapcast: SnapcastManager,
    private val tunnel: TunnelManager,
) {
    fun run(name: String, value: String?): Route? {
        Log.i(TAG, "dbg $name${value?.let { " value=$it" }.orEmpty()}")
        when (name) {
            "station" -> return Route.StationDebug
            "player" -> return Route.Player
            "dump" -> dumpToLog()
            "like" -> withCurrent(name) { engine.toggleHeart(it) }
            "skip" -> player.next()
            "liked" -> player.start(PlaySource.Liked(shuffle = false))
            "likedradio" -> player.start(PlaySource.LikedRadio)
            "share" -> logShare()
            "publiclink" -> setPublicLink(value)
            "leave" -> if (snapcast.state.value.listeningTo == null) {
                Log.w(TAG, "dbg leave: not listening to a station")
            } else {
                snapcast.leave()
            }
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

    /** What the multiroom QR would show right now, for a test that joins without a camera. */
    private fun logShare() {
        val snap = snapcast.state.value
        // The QR dialog's LAN half: one join URL per usefulLocalIps() address on snapserver's port.
        val lan = if (snap.isBroadcasting) {
            usefulLocalIps().map { JoinUrl.build(it.address, snap.broadcastHttpPort) }
        } else {
            emptyList()
        }
        val public = when (val state = tunnel.state.value) {
            is TunnelManager.TunnelState.Active -> publicJoinUrl(state.publicUrl)
            TunnelManager.TunnelState.Starting -> "starting"
            is TunnelManager.TunnelState.Error -> "error:${state.message}"
            TunnelManager.TunnelState.Off -> "off"
        }
        Log.i(
            SnapcastManager.TAG,
            "share lan=${lan.joinToString(",").ifEmpty { "none" }} public=$public " +
                "broadcasting=${snap.isBroadcasting} listening=${snap.listeningTo ?: "-"}",
        )
    }

    /** The multiroom QR's Cloudflare chip (on) and its Turn off button (off). */
    private fun setPublicLink(value: String?) {
        when (value) {
            "on" -> tunnel.setRequested(true)
            "off" -> tunnel.setRequested(false)
            else -> Log.w(TAG, "dbg publiclink: needs --es $EXTRA_VALUE on|off, got '$value'")
        }
    }

    companion object {
        const val EXTRA = "dbg"

        /** The argument of a command that takes one: `--es value <v>`. */
        const val EXTRA_VALUE = "value"
        private const val TAG = "TubeStation"
        private val NAMES = listOf(
            "station",
            "player",
            "dump",
            "like",
            "skip",
            "liked",
            "likedradio",
            "share",
            "publiclink",
            "leave",
        )
    }
}
