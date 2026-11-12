package tech.capullo.tubecapullo.links

import tech.capullo.tubecapullo.data.youtube.YouTubeUrls

/** What an incoming link names, read from the text alone (no network call yet). */
sealed interface LinkTarget {
    data class Video(val videoId: String) : LinkTarget

    data class Playlist(val playlistId: String) : LinkTarget

    /** A YouTube link that names neither: a channel, the home page, a Music album page. */
    data object OtherYouTube : LinkTarget

    /** No YouTube link in the text at all. */
    data object NotYouTube : LinkTarget
}

/**
 * Finds the link in shared text (ACTION_SEND) or a tapped URL (ACTION_VIEW). Shared text often
 * wraps the link: a title before it ("Never Gonna Give You Up https://youtu.be/..."), brackets or
 * a full stop around it, a second line. Pure Kotlin (no android.net.Uri), so it runs in JVM tests;
 * reading the ids out of one link is [YouTubeUrls]'s job, the same parsing the search box uses.
 */
object IncomingLinks {

    /**
     * The first YouTube link in [text] that names a video or a playlist. Words are only considered
     * when their host is YouTube's, so a bare 11-character word in a title never reads as a video
     * id. A YouTube link that names neither is skipped in favour of a later one; when that is all
     * there is, the result is [LinkTarget.OtherYouTube], so the message can say what was wrong.
     */
    fun find(text: String?): LinkTarget {
        if (text.isNullOrBlank()) return LinkTarget.NotYouTube
        var sawYouTube = false
        for (word in text.split(WHITESPACE)) {
            val candidate = word.trim { it in WRAPPING }
            if (!isYouTubeHost(hostOf(candidate))) continue
            sawYouTube = true
            classify(candidate)?.let { return it }
        }
        return if (sawYouTube) LinkTarget.OtherYouTube else LinkTarget.NotYouTube
    }

    /**
     * A playlist link opens the playlist, the same rule as the search box: a watch link that
     * carries `&list=` means "this list", a YouTube Mix (`list=RD...`) included, so a shared Mix
     * plays as that Mix (owner decision 2026-11-11). "Go to radio" on a song is how to get our
     * own song radio from it instead.
     */
    private fun classify(link: String): LinkTarget? {
        YouTubeUrls.parsePlaylistId(link)?.let { return LinkTarget.Playlist(it) }
        return YouTubeUrls.parseVideoId(link)?.let { LinkTarget.Video(it) }
    }

    // Lowercased host of a link with or without a scheme; "" when the word has none. Any text
    // glued before the scheme ("Watch:https://...") falls away with the scheme, as it does in
    // YouTubeUrls' own splitting.
    private fun hostOf(word: String): String = word.substringAfter("://", word)
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .lowercase()

    // YouTube's domains, any subdomain. Broader on purpose than the hosts YouTubeUrls parses: this
    // only answers "is it YouTube", so a gaming. or other subdomain link gets the "not a video or
    // playlist" message rather than "not a YouTube link".
    private fun isYouTubeHost(host: String): Boolean = host == YOUTUBE_DOMAIN ||
        host.endsWith(".$YOUTUBE_DOMAIN") ||
        host == SHORT_DOMAIN ||
        host.endsWith(".$SHORT_DOMAIN")

    private const val YOUTUBE_DOMAIN = "youtube.com"
    private const val SHORT_DOMAIN = "youtu.be"

    private val WHITESPACE = Regex("""\s+""")

    /** Punctuation that wraps a link in prose: quotes, brackets, and sentence marks after it.
     *  Never `-` or `_`: both are valid last characters of a video id. */
    private const val WRAPPING = "\"'`()[]{}<>«»“”‘’.,;:!?…"
}
