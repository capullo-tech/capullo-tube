package tech.capullo.tubecapullo.snapcast

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
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
import tech.capullo.audio.snapcast.JoinTarget
import tech.capullo.audio.snapcast.ServerGetStatusResponse
import tech.capullo.audio.snapcast.ServerOnUpdate
import tech.capullo.audio.snapcast.ServerStatusResult
import tech.capullo.audio.snapcast.SnapcastControlClient
import tech.capullo.audio.snapcast.SnapclientProcess
import tech.capullo.audio.snapcast.SnapcontrolPlugin
import tech.capullo.audio.snapcast.SnapserverListenInfo
import tech.capullo.audio.snapcast.SnapserverNsdRegistrar
import tech.capullo.audio.snapcast.SnapserverPorts
import tech.capullo.audio.snapcast.SnapserverProcess
import tech.capullo.audio.snapcast.Volume
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SnapcastManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class SnapcastState(
        val isBroadcasting: Boolean = false,
        val groups: List<Group> = emptyList(),
        val streamId: String = "",
        val broadcastHttpPort: Int = 0,
        /** Host of the station this device joined from its join URL; null when it plays its own. */
        val listeningTo: String? = null,
        /** The local snapclient's connection, whichever station it plays. */
        val clientConnection: SnapclientProcess.ConnectionState = SnapclientProcess.ConnectionState.STARTING,
    )

    private val _state = MutableStateFlow(SnapcastState())
    val state: StateFlow<SnapcastState> = _state.asStateFlow()

    val localClientId: String get() = SnapclientProcess.localHostId(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var snapserverProcess: SnapserverProcess? = null
    private var snapcontrolPlugin: SnapcontrolPlugin? = null
    private var nsdRegistrar: SnapserverNsdRegistrar? = null
    private var snapserverJob: Job? = null

    private var snapclientProcess: SnapclientProcess? = null
    private var snapclientJob: Job? = null
    private var snapclientStateJob: Job? = null
    private var currentSnapclient: ClientTarget? = null
    private var joinJob: Job? = null

    private var audioFocus: AudioFocusController? = null

    private var controlClient: SnapcastControlClient? = null
    private var controlJob: Job? = null

    fun prepareBroadcast(): String {
        stopBroadcast()
        return SnapserverProcess(
            context,
            STREAM_NAME,
            SnapserverPorts.free(),
            controlSocketName = SnapserverProcess.controlSocketName(context),
        ).also { snapserverProcess = it }.pipeFilepath
    }

    fun startBroadcast(nowPlaying: StateFlow<NowPlaying>, controller: PlaybackController) {
        // Playing here takes the speaker back from a joined station.
        leave()
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
        snapcontrolPlugin = SnapcontrolPlugin(
            nowPlaying,
            controller,
            scope.coroutineContext[Job]!!,
            socketName = snapserver.controlSocketName,
        ).also { it.start() }
        snapserverJob = scope.launch { snapserver.start() }
        startLocalSnapclient(ClientTarget("localhost", ports.streamPort))
        nsdRegistrar = SnapserverNsdRegistrar(context)
            .also { it.start(STREAM_NAME, ports.streamPort, ports.tcpPort, ports.httpPort) }
        startControl("localhost", ports.httpPort)
        _state.update { it.copy(isBroadcasting = true, broadcastHttpPort = ports.httpPort) }
    }

    fun notifyPropertiesChanged() {
        snapcontrolPlugin?.notifyPropertiesChanged()
    }

    fun reclaimAudioFocus() {
        audioFocus?.let {
            it.request()
            it.refocus()
        }
    }

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
        // A joined station outlives our broadcast stack: its snapclient is not ours to stop.
        if (_state.value.listeningTo == null) stopLocalSnapclient()
        snapserverJob?.cancel()
        snapserverJob = null
        snapserverProcess = null
        _state.update { it.copy(isBroadcasting = false, groups = emptyList(), streamId = "") }
    }

    /**
     * Listens to the station a join URL points at: this device's snapclient leaves our own snapserver
     * for it. Our broadcast stack keeps running; the caller pauses local playback. Through the tunnel
     * snapclient streams over `wss` on the tunnel's 443; on the LAN the station's `listen.json` names
     * the stream port, with the URL's port-block guess as the fallback. Ended by [leave], or by
     * playing something here ([startBroadcast]).
     */
    fun join(station: JoinTarget) {
        Log.i(TAG, "join host=${station.host} httpPort=${station.httpPort} secure=${station.secure}")
        joinJob?.cancel()
        _state.update {
            it.copy(listeningTo = station.host, clientConnection = SnapclientProcess.ConnectionState.STARTING)
        }
        joinJob = scope.launch {
            val client = if (station.secure) {
                ClientTarget(station.host, station.port, station.scheme)
            } else {
                val ports = SnapserverListenInfo.fetch(station.host, station.httpPort) ?: station.ports
                ClientTarget(station.host, ports?.streamPort ?: station.port)
            }
            ensureActive()
            Log.i(TAG, "join dial=${client.scheme}://${client.host}:${client.port}")
            startLocalSnapclient(client)
        }
    }

    /** Stops listening to a joined station; our own broadcast, if running, gets the speaker back. */
    fun leave() {
        if (_state.value.listeningTo == null) return
        Log.i(TAG, "leave host=${_state.value.listeningTo}")
        joinJob?.cancel()
        joinJob = null
        _state.update { it.copy(listeningTo = null) }
        val server = snapserverProcess
        if (_state.value.isBroadcasting && server != null) {
            startLocalSnapclient(ClientTarget("localhost", server.ports.streamPort))
        } else {
            stopLocalSnapclient()
        }
    }

    private fun startLocalSnapclient(target: ClientTarget) {
        destroySnapclientProcess()
        currentSnapclient = target
        launchSnapclientProcess(target)
        val focus = audioFocus ?: AudioFocusController(
            context,
            onPause = { scope.launch { destroySnapclientProcess() } },
            onResume = {
                scope.launch {
                    val t = currentSnapclient
                    if (t != null && snapclientProcess == null) {
                        launchSnapclientProcess(t)
                    }
                }
            },
        ).also { audioFocus = it }
        focus.request()
    }

    private fun launchSnapclientProcess(target: ClientTarget) {
        val sc = SnapclientProcess(context).also { snapclientProcess = it }
        snapclientStateJob = scope.launch {
            sc.connectionState.collect { c -> _state.update { it.copy(clientConnection = c) } }
        }
        snapclientJob = scope.launch { sc.start(target.host, target.port, scheme = target.scheme) }
    }

    private fun destroySnapclientProcess() {
        snapclientStateJob?.cancel()
        snapclientStateJob = null
        snapclientJob?.cancel()
        snapclientJob = null
        snapclientProcess?.destroy()
        snapclientProcess = null
    }

    private fun stopLocalSnapclient() {
        destroySnapclientProcess()
        currentSnapclient = null
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
        val groupsWithConnected = groups.filter { g -> g.clients.any { it.connected } }
        if (groupsWithConnected.size <= 1) return
        val targetGroupId = groupsWithConnected.first().id
        val connectedIds = groups.flatMap { it.clients }.filter { it.connected }.map { it.id }
        scope.launch { controlClient?.sendGroupSetClients(targetGroupId, connectedIds) }
        Log.d(TAG, "Merging ${groupsWithConnected.size} groups → $targetGroupId (${connectedIds.size} clients)")
    }

    /** Where the local snapclient dials: our own snapserver, or a joined station. */
    private data class ClientTarget(val host: String, val port: Int, val scheme: String = "tcp")

    companion object {
        private const val TAG = "SnapcastManager"

        private const val STREAM_NAME = "CapulloTube"
    }
}
