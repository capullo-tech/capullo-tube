package tech.capullo.tubecapullo.data.youtube

data class YtVideo(
    val videoId: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val uploaderUrl: String? = null,
    val viewCount: Long = -1,
    val uploadYear: Int? = null,
    val uploaderVerified: Boolean = false,
)

data class YtRelated(val seedViews: Long, val videos: List<YtVideo>)

data class YtChapter(val title: String, val startMs: Long)

data class YtAlbum(val video: YtVideo, val chapters: List<YtChapter>)

data class YtVideoList(val title: String, val videos: List<YtVideo>)

/**
 * One region's chart and the HTTP status charts.youtube.com answered that region's own request with,
 * 0 when no answer came back (DNS miss, refused connection, timeout). Regions are fetched concurrently
 * against the same host, so the status travels with its result.
 */
data class YtChart(val videos: Result<List<YtVideo>>, val httpStatus: Int)

enum class CollectionKind { PLAYLISTS, ALBUMS, CHANNELS }

data class YtCollection(
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val itemCount: Long?,
)

data class YtStream(
    val url: String,
    val mimeType: String,
    val bitrateKbps: Int,
    val contentLength: Long?,
    val expiresAtEpochSeconds: Long?,
    val itag: Int,
    val isM4a: Boolean,
)

sealed class YouTubeError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class BotCheck(cause: Throwable? = null) :
        YouTubeError(
            "YouTube asked for a bot check. Playback needs a proof-of-origin token.",
            cause,
        )

    class Unavailable(reason: String, cause: Throwable? = null) : YouTubeError(reason, cause)

    class Failed(reason: String, cause: Throwable? = null) : YouTubeError(reason, cause)
}
