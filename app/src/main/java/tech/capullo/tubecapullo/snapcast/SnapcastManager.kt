package tech.capullo.tubecapullo.snapcast

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.capullo.audio.contracts.NowPlaying
import tech.capullo.audio.contracts.PlaybackController
import tech.capullo.audio.player.AudioFocusController
import tech.capullo.audio.snapcast.ClientOnConnect
import tech.capullo.audio.snapcast.ClientOnDisconnect
import tech.capullo.audio.snapcast.ClientOnLatencyChanged
import tech.capullo.audio.snapcast.ClientOnNameChanged
import tech.capullo.audio.snapcast.ClientOnVolumeChanged
import tech.capullo.audio.snapcast.Group
import tech.capullo.audio.snapcast.ServerGetStatusResponse
import tech.capullo.audio.snapcast.ServerOnUpdate
import tech.capullo.audio.snapcast.ServerStatusResult
import tech.capullo.audio.snapcast.SnapcastControlClient
import tech.capullo.audio.snapcast.SnapclientProcess
import tech.capullo.audio.snapcast.SnapcontrolPlugin
import tech.capullo.audio.snapcast.SnapserverNsdRegistrar
import tech.capullo.audio.snapcast.SnapserverPorts
import tech.capullo.audio.snapcast.SnapserverProcess
import tech.capullo.audio.snapcast.Volume
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The broadcast stack: snapserver + FIFO, the local snapclient (this phone's audible output), the
 * snapcontrol metadata plugin, NSD advertisement, and the JSON-RPC control connection that keeps
 * [SnapcastState.groups] (the listener list) current.
 *
 * Ported from telecloud-radio's SnapcastManager, minus everything that has no caller here yet
 * (CONTRIBUTING §10): listen-in mode, per-client channel tags and volume/latency persistence, the
 * stream-control lock, web-config toggles, and the renameable server name. Telecloud is the
 * reference when any of those come back - its copies carry the interlock reasoning this trim
 * removed.
 */
@Singleton
class SnapcastManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class SnapcastState(
        val isBroadcasting: Boolean = false,
        /** Server-reported groups → clients: the listener list, kept live by the control client. */
        val groups: List<Group> = emptyList(),
        /** Stream id of the active group (needed for Stream.Control later). */
        val streamId: String = "",
        /** This broadcaster's resolved HTTP (web player + control) port - for the listen URL. */
        val broadcastHttpPort: Int = 0,
    )

    private val _state = MutableStateFlow(SnapcastState())
    val state: StateFlow<SnapcastState> = _state.asStateFlow()

    /** This device's persistent snapclient id - marks "this device" in client lists. */
    val localClientId: String get() = SnapclientProcess.localHostId(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // --- Broadcast side ---
    private var snapserverProcess: SnapserverProcess? = null
    private var snapcontrolPlugin: SnapcontrolPlugin? = null
    private var nsdRegistrar: SnapserverNsdRegistrar? = null
    private var snapserverJob: Job? = null

    // --- Local audible output ---
    private var snapclientProcess: SnapclientProcess? = null
    private var snapclientJob: Job? = null
    private var currentSnapclientHost: String? = null
    private var currentSnapclientPort: Int = 0

    // Audio focus governs THIS device's audible snapclient (not the broadcast): snapserver keeps
    // streaming to web/LAN clients regardless, but the local snapclient is paused when another app
    // takes focus and restarted when focus returns - so two capullo apps don't mix on one speaker.
    private var audioFocus: AudioFocusController? = null

    private var controlClient: SnapcastControlClient? = null
    private var controlJob: Job? = null

    /**
     * (Re)creates the snapserver process + FIFO. Called by PlaybackService in onCreate BEFORE the
     * tee sink opens the pipe, so the write end always refers to the freshly created FIFO inode.
     */
    fun prepareBroadcast(): String {
        stopBroadcast()
        // OS-assigned ports so multiple capullo apps coexist and the ports aren't a fixed guess.
        // The resolved trio is read back off snapserver.ports to wire the snapclient / NSD / control.
        return SnapserverProcess(
            context,
            STREAM_NAME,
            SnapserverPorts.free(),
            controlSocketName = SnapserverProcess.controlSocketName(context),
        ).also { snapserverProcess = it }.pipeFilepath
    }

    /**
     * Starts snapserver + local snapclient + metadata plugin + NSD + control socket. Idempotent -
     * called on every "playing" transition.
     */
    fun startBroadcast(nowPlaying: StateFlow<NowPlaying>, controller: PlaybackController) {
        if (_state.value.isBroadcasting) return
        val snapserver = snapserverProcess
            ?: SnapserverProcess(
                context,
                STREAM_NAME,
                SnapserverPorts.free(),
                controlSocketName = SnapserverProcess.controlSocketName(context),
            ).also { snapserverProcess = it }
        val ports = snapserver.ports
        Log.d(TAG, "Starting broadcast stack (stream=${ports.streamPort} http=${ports.httpPort})")
        // Plugin must be listening before snapserver spawns libsnapcontrol.so, and must bind the
        // SAME per-app socket name the snapserver told libsnapcontrol.so to connect to.
        snapcontrolPlugin = SnapcontrolPlugin(
            nowPlaying,
            controller,
            scope.coroutineContext[Job]!!,
            socketName = snapserver.controlSocketName,
        ).also { it.start() }
        snapserverJob = scope.launch { snapserver.start() }
        startLocalSnapclient("localhost", ports.streamPort)
        nsdRegistrar = SnapserverNsdRegistrar(context)
            .also { it.start(STREAM_NAME, ports.streamPort, ports.tcpPort, ports.httpPort) }
        startControl("localhost", ports.httpPort)
        _state.update { it.copy(isBroadcasting = true, broadcastHttpPort = ports.httpPort) }
    }

    fun notifyPropertiesChanged() {
        snapcontrolPlugin?.notifyPropertiesChanged()
    }

    /**
     * Explicit user PLAY: reclaim the speaker for our local snapclient. [AudioFocusController.request]
     * unconditionally (re)acquires audio focus, so a co-broadcasting app's local snapclient is
     * evicted; [AudioFocusController.refocus] then restarts our own snapclient if a prior focus loss
     * had stopped it. No-op when not broadcasting (audioFocus is null).
     */
    fun reclaimAudioFocus() {
        audioFocus?.let {
            it.request()
            it.refocus()
        }
    }

    /** Client.SetVolume, applied optimistically so the sheet's slider doesn't jump back while the
     *  server round-trips (ported from telecloud, minus its volume persistence). */
    fun adjustClientVolume(clientId: String, percent: Int, muted: Boolean) {
        _state.update { st ->
            st.copy(
                groups = st.groups.map { group ->
                    group.copy(
                        clients = group.clients.map { c ->
                            if (c.id == clientId) c.copy(config = c.config.copy(volume = Volume(muted, percent))) else c
                        },
                    )
                },
            )
        }
        scope.launch { controlClient?.sendSetVolume(clientId, muted, percent) }
    }

    fun adjustClientLatency(clientId: String, latencyMs: Int) {
        scope.launch { controlClient?.sendSetLatency(clientId, latencyMs) }
    }

    fun stopBroadcast() {
        if (snapserverProcess == null && snapcontrolPlugin == null) return
        Log.d(TAG, "Stopping broadcast stack")
        nsdRegistrar?.stop()
        nsdRegistrar = null
        snapcontrolPlugin?.stop()
        snapcontrolPlugin = null
        stopControl()
        stopLocalSnapclient()
        snapserverJob?.cancel()
        snapserverJob = null
        snapserverProcess = null
        _state.update { it.copy(isBroadcasting = false, groups = emptyList(), streamId = "") }
    }

    // --- Internals ---

    private fun startLocalSnapclient(host: String, port: Int) {
        destroySnapclientProcess()
        currentSnapclientHost = host
        currentSnapclientPort = port
        launchSnapclientProcess(host, port)
        // Request audio focus so the foreground app owns the speaker. On loss the local snapclient
        // is stopped (broadcast continues); on regain it's restarted with the same host/port.
        val focus = audioFocus ?: AudioFocusController(
            context,
            onPause = { scope.launch { destroySnapclientProcess() } },
            onResume = {
                scope.launch {
                    val h = currentSnapclientHost
                    if (h != null && snapclientProcess == null) {
                        launchSnapclientProcess(h, currentSnapclientPort)
                    }
                }
            },
        ).also { audioFocus = it }
        focus.request()
    }

    private fun launchSnapclientProcess(host: String, port: Int) {
        val sc = SnapclientProcess(context).also { snapclientProcess = it }
        snapclientJob = scope.launch { sc.start(host, port) }
    }

    private fun destroySnapclientProcess() {
        snapclientJob?.cancel()
        snapclientJob = null
        snapclientProcess?.destroy()
        snapclientProcess = null
    }

    private fun stopLocalSnapclient() {
        destroySnapclientProcess()
        currentSnapclientHost = null
        audioFocus?.abandon()
    }

    private fun startControl(host: String, httpPort: Int) {
        stopControl()
        val client = SnapcastControlClient(host, httpPort).also { controlClient = it }
        controlJob = scope.launch {
            client.initialize()
            client.notifications.collect { notif ->
                when (notif) {
                    is ServerGetStatusResponse -> applyServer(notif.result)
                    is ServerOnUpdate -> applyServer(notif.params)
                    is ClientOnVolumeChanged -> _state.update { st ->
                        st.copy(
                            groups = st.groups.map { group ->
                                group.copy(
                                    clients = group.clients.map { c ->
                                        if (c.id == notif.params.clientId) {
                                            c.copy(config = c.config.copy(volume = notif.params.volume))
                                        } else {
                                            c
                                        }
                                    },
                                )
                            },
                        )
                    }
                    is ClientOnLatencyChanged -> _state.update { st ->
                        st.copy(
                            groups = st.groups.map { group ->
                                group.copy(
                                    clients = group.clients.map { c ->
                                        if (c.id == notif.params.clientId) {
                                            c.copy(config = c.config.copy(latency = notif.params.latency))
                                        } else {
                                            c
                                        }
                                    },
                                )
                            },
                        )
                    }
                    is ClientOnConnect,
                    is ClientOnDisconnect,
                    is ClientOnNameChanged,
                    -> controlClient?.sendGetStatus()
                    else -> {}
                }
            }
        }
    }

    private fun stopControl() {
        controlJob?.cancel()
        controlJob = null
        runCatching { controlClient?.close() }
        controlClient = null
    }

    private suspend fun applyServer(result: ServerStatusResult) {
        val groups = result.server.groups
        _state.update {
            it.copy(groups = groups, streamId = groups.firstOrNull()?.streamId ?: it.streamId)
        }
        mergeGroupsIfNeeded(groups)
    }

    private fun mergeGroupsIfNeeded(groups: List<Group>) {
        // Do NOT delete disconnected clients: web players briefly disconnect when backgrounded, then
        // reconnect with the same id - deleting wipes their stored name and latency calibration.
        // Merge ONLY when CONNECTED clients are split across multiple groups; groups holding only
        // disconnected clients are ignored (avoids a SetClients/OnUpdate storm).
        val groupsWithConnected = groups.filter { g -> g.clients.any { it.connected } }
        if (groupsWithConnected.size <= 1) return
        val targetGroupId = groupsWithConnected.first().id
        val connectedIds = groups.flatMap { it.clients }.filter { it.connected }.map { it.id }
        scope.launch { controlClient?.sendGroupSetClients(targetGroupId, connectedIds) }
        Log.d(TAG, "Merging ${groupsWithConnected.size} groups → $targetGroupId (${connectedIds.size} clients)")
    }

    companion object {
        private const val TAG = "SnapcastManager"

        /** The Snapcast stream `name=` identity for this app (web player + snapclients + NSD). */
        private const val STREAM_NAME = "TubeCapullo"
    }
}
