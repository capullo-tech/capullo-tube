package tech.capullo.tubecapullo.data.youtube

/**
 * Pure URL/ID parsing for the YouTube source. No Android or extractor types on purpose: this is the
 * part that's cheap to unit-test on the JVM, and it's the part that decides whether a pasted string
 * is a video, a playlist, or junk - before any network call happens.
 */
object YouTubeUrls {

    // YouTube video IDs are 11 chars of base64url. Anchored, so a bare ID is only accepted when the
    // WHOLE string is one - otherwise any 11-char run inside a sentence would match.
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    // Playlist IDs vary in length and prefix (PL, UU, LL, RD, OLAK5uy_, ...), so this stays loose.
    private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{12,42}$")

    private val HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "www.youtu.be",
    )

    /**
     * The video ID in [input], or null if there isn't one. Accepts a bare ID, a watch URL, youtu.be,
     * /shorts/, /embed/, /v/ and the music subdomain, with or without a scheme.
     */
    fun parseVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (VIDEO_ID.matches(trimmed)) return trimmed

        val parts = split(trimmed) ?: return null
        if (parts.host !in HOSTS) return null

        // youtu.be/<id> - the ID is the whole path.
        if (parts.host.endsWith("youtu.be")) {
            return parts.segments.firstOrNull()?.takeIf { VIDEO_ID.matches(it) }
        }
        // /watch?v=<id> - and ?v= also works on any other path YouTube accepts.
        parts.query["v"]?.let { if (VIDEO_ID.matches(it)) return it }
        // /shorts/<id>, /embed/<id>, /v/<id>, /live/<id>
        val segments = parts.segments
        if (segments.size >= 2 && segments[0] in setOf("shorts", "embed", "v", "live")) {
            return segments[1].takeIf { VIDEO_ID.matches(it) }
        }
        return null
    }

    /**
     * The playlist ID in [input], or null. A watch URL carrying `&list=` counts: pasting "this song,
     * from this playlist" should be able to mean the playlist.
     */
    fun parsePlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val parts = split(trimmed)
            ?: return trimmed.takeIf { PLAYLIST_ID.matches(it) && looksLikePlaylistId(it) }
        if (parts.host !in HOSTS) return null
        return parts.query["list"]?.takeIf { PLAYLIST_ID.matches(it) }
    }

    /** True when [input] names a playlist rather than a single video. Order matters: a watch URL
     * with `&list=` is both, and the caller usually wants the playlist. */
    fun isPlaylist(input: String): Boolean = parsePlaylistId(input) != null

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    fun playlistUrl(playlistId: String): String = "https://www.youtube.com/playlist?list=$playlistId"

    /** The Mix ("my radio") playlist ID YouTube derives from a single video. */
    fun mixPlaylistId(videoId: String): String = "$MIX_PREFIX$videoId"

    /** True for a Mix playlist ID, whose pages cycle instead of ending. */
    fun isMixPlaylistId(playlistId: String): Boolean = playlistId.startsWith(MIX_PREFIX)

    /**
     * The URL to fetch a video's Mix from. The probe (run 2026-08-18) showed BOTH this
     * watch?v=…&list=RD… form and the bare playlist?list=RD… form resolve through the extractor;
     * the watch form is kept because it is the shape a browser shows and it pins the seed
     * explicitly. docs/probes/rd-mix-results.md has the run data.
     */
    fun mixUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId&list=${mixPlaylistId(videoId)}"

    /**
     * Unix seconds at which a googlevideo stream URL stops working, from its `expire` parameter
     * (typically ~6 h out). Null when the URL doesn't carry one, in which case the caller should
     * treat the stream as short-lived rather than permanent.
     */
    fun streamExpiryEpochSeconds(url: String): Long? = split(url)?.query?.get("expire")?.toLongOrNull()

    // YouTube's prefix for the Mix playlists it generates from a video.
    private const val MIX_PREFIX = "RD"

    // User playlists, uploads, liked, mixes, and the album lists YouTube Music hands out.
    private val PLAYLIST_PREFIXES = listOf("PL", "UU", "LL", MIX_PREFIX, "OLAK5uy_", "FL", "SP")

    // A bare ID with no URL around it is ambiguous - "dQw4w9WgXcQ" is a valid video ID and also
    // matches the loose playlist pattern. Only treat it as a playlist if it carries a known prefix.
    private fun looksLikePlaylistId(id: String): Boolean = PLAYLIST_PREFIXES.any { id.startsWith(it) }

    private data class Parts(
        val host: String,
        val segments: List<String>,
        val query: Map<String, String>,
    )

    // Hand-rolled rather than java.net.URI/android.net.Uri: URI rejects some of the malformed-ish
    // strings people actually paste (spaces, missing scheme), and android.net.Uri isn't available
    // in plain JVM unit tests.
    private fun split(raw: String): Parts? {
        val noScheme = raw.substringAfter("://", raw)
        val hostAndRest = noScheme.substringBefore('#')
        val host = hostAndRest.substringBefore('/').substringBefore('?').lowercase()
        if (host.isEmpty() || '.' !in host) return null

        val afterHost = hostAndRest.removePrefix(host)
        val path = afterHost.substringBefore('?')
        val query = afterHost.substringAfter('?', "")

        return Parts(
            host = host,
            segments = path.split('/').filter { it.isNotEmpty() },
            query = query.split('&')
                .filter { it.isNotEmpty() && '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') },
        )
    }
}
