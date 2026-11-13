package tech.capullo.tubecapullo.links

enum class LinkVia(val logName: String) {
    SHARE("share"),

    VIEW("view"),
}

enum class RejectReason(val logName: String) {
    NOT_YOUTUBE("not-youtube"),
    NOT_A_VIDEO("not-a-video"),
}

sealed interface LinkAction {
    val logName: String

    sealed interface ForVideo : LinkAction {
        val videoId: String
    }

    data class PlayNow(override val videoId: String) : ForVideo {
        override val logName get() = "play-now"
    }

    data class PlayNext(override val videoId: String) : ForVideo {
        override val logName get() = "play-next"
    }

    data class Ask(override val videoId: String, val canPlayNext: Boolean) : ForVideo {
        override val logName get() = "ask"
    }

    data class OpenPlaylist(val playlistId: String) : LinkAction {
        override val logName get() = "open-playlist"
    }

    data class Reject(val reason: RejectReason) : LinkAction {
        override val logName get() = "reject"
    }
}

fun decideLinkAction(target: LinkTarget, somethingPlays: Boolean, askFirst: Boolean): LinkAction = when (target) {
    is LinkTarget.Video -> when {
        askFirst -> LinkAction.Ask(target.videoId, canPlayNext = somethingPlays)
        somethingPlays -> LinkAction.PlayNext(target.videoId)
        else -> LinkAction.PlayNow(target.videoId)
    }
    is LinkTarget.Playlist -> LinkAction.OpenPlaylist(target.playlistId)
    LinkTarget.OtherYouTube -> LinkAction.Reject(RejectReason.NOT_A_VIDEO)
    LinkTarget.NotYouTube -> LinkAction.Reject(RejectReason.NOT_YOUTUBE)
}
