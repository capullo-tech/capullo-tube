package tech.capullo.tubecapullo.data.youtube

object YouTubeUrls {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")

    private val PLAYLIST_ID = Regex("^[A-Za-z0-9_-]{12,42}$")

    private val HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "www.youtu.be",
    )

    fun parseVideoId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (VIDEO_ID.matches(trimmed)) return trimmed

        val parts = split(trimmed) ?: return null
        if (parts.host !in HOSTS) return null

        if (parts.host.endsWith("youtu.be")) {
            return parts.segments.firstOrNull()?.takeIf { VIDEO_ID.matches(it) }
        }
        parts.query["v"]?.let { if (VIDEO_ID.matches(it)) return it }
        val segments = parts.segments
        if (segments.size >= 2 && segments[0] in setOf("shorts", "embed", "v", "live")) {
            return segments[1].takeIf { VIDEO_ID.matches(it) }
        }
        return null
    }

    fun parsePlaylistId(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val parts = split(trimmed)
            ?: return trimmed.takeIf { PLAYLIST_ID.matches(it) && looksLikePlaylistId(it) }
        if (parts.host !in HOSTS) return null
        return parts.query["list"]?.takeIf { PLAYLIST_ID.matches(it) }
    }

    fun isPlaylist(input: String): Boolean = parsePlaylistId(input) != null

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

    fun playlistUrl(playlistId: String): String = "https://www.youtube.com/playlist?list=$playlistId"

    fun mixPlaylistId(videoId: String): String = "$MIX_PREFIX$videoId"

    fun isMixPlaylistId(playlistId: String): Boolean = playlistId.startsWith(MIX_PREFIX)

    fun mixUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId&list=${mixPlaylistId(videoId)}"

    fun streamExpiryEpochSeconds(url: String): Long? = split(url)?.query?.get("expire")?.toLongOrNull()

    private const val MIX_PREFIX = "RD"

    private val PLAYLIST_PREFIXES = listOf("PL", "UU", "LL", MIX_PREFIX, "OLAK5uy_", "FL", "SP")

    private fun looksLikePlaylistId(id: String): Boolean = PLAYLIST_PREFIXES.any { id.startsWith(it) }

    private data class Parts(
        val host: String,
        val segments: List<String>,
        val query: Map<String, String>,
    )

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
