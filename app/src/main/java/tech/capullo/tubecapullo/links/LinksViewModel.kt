package tech.capullo.tubecapullo.links

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.youtube.YouTubeError
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YouTubeUrls
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.player.PlaybackCoordinator
import tech.capullo.tubecapullo.station.PlaySource
import tech.capullo.tubecapullo.station.StationEngine
import tech.capullo.tubecapullo.ui.nav.Route
import tech.capullo.tubecapullo.util.TitleCleaner
import javax.inject.Inject

data class LinkChoice(val video: YtVideo, val canPlayNext: Boolean)

enum class LinkPick(val logName: String) {
    PLAY_NOW("play-now"),
    PLAY_NEXT("play-next"),
    LIKE("like"),
    CANCEL("cancel"),
}

sealed interface LinkEffect {
    data class Open(val route: Route) : LinkEffect

    data class Snackbar(val text: String) : LinkEffect

    data class Toast(val text: String) : LinkEffect
}

@HiltViewModel
class LinksViewModel @Inject constructor(
    private val extractor: YouTubeExtractorClient,
    private val player: PlaybackCoordinator,
    private val stationEngine: StationEngine,
    private val settings: AppSettings,
    private val playlistLinks: PlaylistLinkRequests,
) : ViewModel() {
    private val _resolving = MutableStateFlow(false)

    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val _choice = MutableStateFlow<LinkChoice?>(null)

    val choice: StateFlow<LinkChoice?> = _choice.asStateFlow()

    private val _effects = Channel<LinkEffect>(Channel.UNLIMITED)
    val effects: Flow<LinkEffect> = _effects.receiveAsFlow()

    private var generation = 0
    private var resolveJob: Job? = null

    fun handle(via: LinkVia, text: String?) {
        val target = IncomingLinks.find(text)
        val action = decideLinkAction(
            target,
            somethingPlays = player.state.value.video != null,
            askFirst = settings.askForSharedLinks.value,
        )
        val reason = (action as? LinkAction.Reject)
            ?.let { " reason=${it.reason.logName}" }
            .orEmpty()
        Log.i(
            TAG,
            "incoming via=${via.logName} id=${target.logId} kind=${target.logKind} " +
                "action=${action.logName}$reason",
        )

        generation++
        resolveJob?.cancel()
        _resolving.value = false
        _choice.value = null

        when (action) {
            is LinkAction.Reject -> _effects.trySend(LinkEffect.Toast(action.reason.message))
            is LinkAction.OpenPlaylist -> {
                playlistLinks.post(YouTubeUrls.playlistUrl(action.playlistId))
                _effects.trySend(LinkEffect.Open(Route.Search))
            }
            is LinkAction.ForVideo -> resolve(action)
        }
    }

    fun choose(pick: LinkPick) {
        val choice = _choice.value ?: return
        _choice.value = null
        Log.i(TAG, "choice id=${choice.video.videoId} pick=${pick.logName}")
        when (pick) {
            LinkPick.PLAY_NOW -> playNow(choice.video)
            LinkPick.PLAY_NEXT -> playNext(choice.video)
            LinkPick.LIKE -> like(choice.video)
            LinkPick.CANCEL -> Unit
        }
    }

    private fun resolve(action: LinkAction.ForVideo) {
        val gen = generation
        _resolving.value = true
        resolveJob = viewModelScope.launch {
            try {
                val video = extractor.video(action.videoId)
                Log.i(TAG, "resolve id=${video.videoId} result=ok title=${q(video.title)}")
                when (action) {
                    is LinkAction.PlayNow -> playNow(video)
                    is LinkAction.PlayNext -> playNext(video)
                    is LinkAction.Ask -> _choice.value = LinkChoice(video, action.canPlayNext)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "resolve id=${action.videoId} result=error error=${q(e.message)}")
                _effects.trySend(LinkEffect.Snackbar("Can't play this link: ${e.readable()}"))
            } finally {
                if (gen == generation) _resolving.value = false
            }
        }
    }

    private fun playNow(video: YtVideo) {
        player.start(PlaySource.SongRadio(video))
        _effects.trySend(LinkEffect.Open(Route.Player))
    }

    private fun playNext(video: YtVideo) {
        if (player.state.value.video == null) {
            playNow(video)
            return
        }
        stationEngine.playNext(video)
        val title = TitleCleaner.clean(video.title, video.uploader)
        _effects.trySend(LinkEffect.Snackbar("Plays next: $title"))
    }

    private fun like(video: YtVideo) {
        if (video.videoId in stationEngine.likedIds.value) {
            _effects.trySend(LinkEffect.Toast("Already in your likes"))
            return
        }
        stationEngine.toggleLike(video)
        _effects.trySend(LinkEffect.Toast("Liked"))
    }

    private val RejectReason.message: String
        get() = when (this) {
            RejectReason.NOT_YOUTUBE -> "Not a YouTube link"
            RejectReason.NOT_A_VIDEO -> "This YouTube link is not a song or a playlist"
        }

    private val LinkTarget.logId: String
        get() = when (this) {
            is LinkTarget.Video -> videoId
            is LinkTarget.Playlist -> playlistId
            LinkTarget.OtherYouTube, LinkTarget.NotYouTube -> "-"
        }

    private val LinkTarget.logKind: String
        get() = when (this) {
            is LinkTarget.Video -> "video"
            is LinkTarget.Playlist -> "playlist"
            LinkTarget.OtherYouTube, LinkTarget.NotYouTube -> "none"
        }

    private fun Throwable.readable(): String = when (this) {
        is YouTubeError -> message ?: "YouTube request failed"
        else -> message ?: "Something went wrong"
    }

    private fun q(text: String?): String = if (text == null) "-" else "\"$text\""

    private companion object {
        const val TAG = "TubeLinks"
    }
}
