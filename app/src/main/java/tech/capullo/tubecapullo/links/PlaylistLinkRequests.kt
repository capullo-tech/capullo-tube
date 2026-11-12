package tech.capullo.tubecapullo.links

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a shared or opened playlist link to the Search tab, which lists it the way it lists a
 * pasted one (SearchViewModel collects [requests]). A channel, not a StateFlow: each link is
 * consumed once, and a link posted before the Search tab's ViewModel exists (its first visit
 * creates it) waits in the channel until then. Conflated: only the newest link matters.
 */
@Singleton
class PlaylistLinkRequests @Inject constructor() {
    private val channel = Channel<String>(Channel.CONFLATED)

    /** Playlist URLs to list in Search, each delivered to one collector once. */
    val requests: Flow<String> = channel.receiveAsFlow()

    fun post(playlistUrl: String) {
        channel.trySend(playlistUrl)
    }
}
