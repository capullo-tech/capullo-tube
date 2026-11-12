package tech.capullo.tubecapullo.links

/** Where a link came in. [logName] is its `via=` value in the `TubeLinks` log. */
enum class LinkVia(val logName: String) {
    /** The share sheet: ACTION_SEND text/plain on MainActivity. */
    SHARE("share"),

    /** A tapped link: ACTION_VIEW through the manifest's link alias. */
    VIEW("view"),
}

/** Why a link was turned down; each reason gets its own message (CONTRIBUTING §4). */
enum class RejectReason(val logName: String) {
    NOT_YOUTUBE("not-youtube"),
    NOT_A_VIDEO("not-a-video"),
}

/** What to do with an incoming link. [logName] is its `action=` value in the `TubeLinks` log. */
sealed interface LinkAction {
    val logName: String

    /** An action on one video: it needs the video's metadata first (the station seed and the
     *  "Plays next" line both show its title). */
    sealed interface ForVideo : LinkAction {
        val videoId: String
    }

    /** Song radio from the video, then the Player. */
    data class PlayNow(override val videoId: String) : ForVideo {
        override val logName get() = "play-now"
    }

    /** Pin the video after the playing track. */
    data class PlayNext(override val videoId: String) : ForVideo {
        override val logName get() = "play-next"
    }

    /** Offer the choices in a dialog; Play next only when [canPlayNext]. */
    data class Ask(override val videoId: String, val canPlayNext: Boolean) : ForVideo {
        override val logName get() = "ask"
    }

    /** The Search tab, with the playlist listed as if its link had been pasted there. */
    data class OpenPlaylist(val playlistId: String) : LinkAction {
        override val logName get() = "open-playlist"
    }

    data class Reject(val reason: RejectReason) : LinkAction {
        override val logName get() = "reject"
    }
}

/**
 * The incoming-link decision table (owner decisions 2026-11-11):
 *
 * | target        | ask setting | something plays | action                          |
 * |---------------|-------------|-----------------|---------------------------------|
 * | video         | off         | no              | play now                        |
 * | video         | off         | yes             | play next                       |
 * | video         | on          | either          | ask (Play next only if playing) |
 * | playlist      | either      | either          | open the playlist in Search     |
 * | other YouTube | either      | either          | reject: not a video or playlist |
 * | not YouTube   | either      | either          | reject: not a YouTube link      |
 *
 * The ask setting does not apply to playlists: every choice in the dialog acts on one video.
 * "Something plays" means the player holds a track, paused or not: a paused station still has a
 * queue to pin a track into.
 */
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
