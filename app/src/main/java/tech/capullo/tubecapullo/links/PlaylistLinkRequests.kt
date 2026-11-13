package tech.capullo.tubecapullo.links

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistLinkRequests @Inject constructor() {
    private val channel = Channel<String>(Channel.CONFLATED)

    val requests: Flow<String> = channel.receiveAsFlow()

    fun post(playlistUrl: String) {
        channel.trySend(playlistUrl)
    }
}
