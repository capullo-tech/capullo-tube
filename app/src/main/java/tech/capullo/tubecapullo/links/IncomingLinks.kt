package tech.capullo.tubecapullo.links

import tech.capullo.audio.snapcast.JoinTarget
import tech.capullo.audio.snapcast.JoinUrl
import tech.capullo.tubecapullo.data.quantumcast.QuantumCastContract
import tech.capullo.tubecapullo.data.youtube.YouTubeUrls

sealed interface LinkTarget {
    data class Video(val videoId: String) : LinkTarget

    data class Playlist(val playlistId: String) : LinkTarget

    /** A capullo station's join URL (its listen QR): this device listens to that station. */
    data class Join(val station: JoinTarget) : LinkTarget

    /** QuantumCast's Play on one of its station playlists: plays that playlist here. */
    data class RadioPlaylist(val playlistId: Long) : LinkTarget

    data object OtherYouTube : LinkTarget

    data object NotYouTube : LinkTarget
}

object IncomingLinks {
    fun find(text: String?): LinkTarget {
        if (text.isNullOrBlank()) return LinkTarget.NotYouTube
        var sawYouTube = false
        for (word in text.split(WHITESPACE)) {
            val candidate = word.trim { it in WRAPPING }
            if (!isYouTubeHost(hostOf(candidate))) {
                JoinUrl.parse(candidate)?.let { return LinkTarget.Join(it) }
                QuantumCastContract.playlistIdOfTracksLink(candidate)
                    ?.let { return LinkTarget.RadioPlaylist(it) }
                continue
            }
            sawYouTube = true
            classify(candidate)?.let { return it }
        }
        return if (sawYouTube) LinkTarget.OtherYouTube else LinkTarget.NotYouTube
    }

    private fun classify(link: String): LinkTarget? {
        YouTubeUrls.parsePlaylistId(link)?.let { return LinkTarget.Playlist(it) }
        return YouTubeUrls.parseVideoId(link)?.let { LinkTarget.Video(it) }
    }

    private fun hostOf(word: String): String = word.substringAfter("://", word)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .lowercase()

    private fun isYouTubeHost(host: String): Boolean = host == YOUTUBE_DOMAIN ||
        host.endsWith(".$YOUTUBE_DOMAIN") ||
        host == SHORT_DOMAIN ||
        host.endsWith(".$SHORT_DOMAIN")

    private const val YOUTUBE_DOMAIN = "youtube.com"
    private const val SHORT_DOMAIN = "youtu.be"

    private val WHITESPACE = Regex("""\s+""")

    private const val WRAPPING = "\"'`()[]{}<>«»“”‘’.,;:!?…"
}
