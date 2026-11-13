package tech.capullo.tubecapullo.data.youtube

/**
 * A YouTube video as it appears in a search result, playlist, mix or related-streams list -
 * metadata only, no stream URL. Resolving the actual audio stream is a second, much more expensive
 * call (see [YouTubeExtractorClient.resolveAudioStream]), so listing stays cheap.
 */
data class YtVideo(
    val videoId: String,
    val title: String,
    val uploader: String?,
    /** Seconds. 0 when unknown, and also 0 for live streams, which this source doesn't handle. */
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    /** The uploader's channel page (`https://www.youtube.com/channel/UC...`), for the Player's
     *  channel line. Null when the extractor did not give one, and for tracks read back from
     *  Room (not stored): the channel line then resolves it with [YouTubeExtractorClient.video]. */
    val uploaderUrl: String? = null,
    /** Views as YouTube lists them (a search, chart or related list rounds them: "1.2M views"
     *  reads 1_200_000). -1 when unknown: always for a Mix item (related-quality probe
     *  2026-11-12, 0 of 300 Mix items carried views) and for tracks read back from Room. */
    val viewCount: Long = -1,
    /** The upload year, null when unknown (Mix items and Room tracks, as [viewCount]). A list
     *  item's date comes from YouTube's "3 years ago" text (90 of 96 related items in the probe
     *  were approximate), so it can be a year off (our inference from how the extractor parses
     *  that text); a single video's [YouTubeExtractorClient.video] date is exact. */
    val uploadYear: Int? = null,
    /** The uploader carries YouTube's verified check mark. The related gate keeps a verified
     *  upload whatever its views (station/Pairs.kt). False when unknown. */
    val uploaderVerified: Boolean = false,
)

/** [YouTubeExtractorClient.relatedStreams]: the videos YouTube lists next to a seed, and the
 *  seed's own views from the same fetch (-1 when unknown), which the related gate compares
 *  them to. */
data class YtRelated(val seedViews: Long, val videos: List<YtVideo>)

/** One YouTube chapter of a video: its [title] and where it starts in the video, ms. */
data class YtChapter(val title: String, val startMs: Long)

/** [YouTubeExtractorClient.album]: a long video and its chapters, in order. [chapters] is empty
 *  when the video has fewer than [MIN_CHAPTERS]: it then plays as one track. */
data class YtAlbum(val video: YtVideo, val chapters: List<YtChapter>)

/** A titled list of videos: a playlist ([YouTubeExtractorClient.playlistInfo]) or a channel's
 *  uploads ([YouTubeExtractorClient.channelUploads]), in YouTube's order. */
data class YtVideoList(val title: String, val videos: List<YtVideo>)

/** What [YouTubeExtractorClient.searchCollections] lists. */
enum class CollectionKind { PLAYLISTS, ALBUMS, CHANNELS }

/** A playlist, album or channel as a search lists it: what a row shows and what a tap starts. */
data class YtCollection(
    /** The playlist (`https://www.youtube.com/playlist?list=...`) or the channel page. */
    val url: String,
    val title: String,
    /** The playlist's owner or the album's artist. Null for a channel, and when not given. */
    val uploader: String?,
    val thumbnailUrl: String?,
    /** Videos in it. Null when YouTube did not say, which the YouTube Music filters never do. */
    val itemCount: Long?,
)

/** A resolved, directly-playable audio-only stream. Short-lived - see [expiresAtEpochSeconds]. */
data class YtStream(
    val url: String,
    /** e.g. `audio/mp4` or `audio/webm`; handed to ExoPlayer. */
    val mimeType: String,
    val bitrateKbps: Int,
    /** Bytes, or null when YouTube didn't report it. */
    val contentLength: Long?,
    /**
     * Unix seconds after which the URL 403s. Roughly 6 h out, but treat it as authoritative rather
     * than assuming: null means "expiry unknown", which the cache treats as immediately stale.
     */
    val expiresAtEpochSeconds: Long?,
    val itag: Int,
    /** True for M4A/AAC (as opposed to WebM/Opus). See [AudioStreamSelector] for why it matters. */
    val isM4a: Boolean,
)

/**
 * Why an extraction failed, in terms the UI can act on. The extractor throws a dozen exception
 * types; most of them mean the same thing to a listener ("this one won't play, skip it"), but
 * [BotCheck] is different - it means *nothing* will play until a PoToken provider exists, and it's
 * the signal the whole YouTube source hinges on.
 */
sealed class YouTubeError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** YouTube demanded proof-of-origin ("Sign in to confirm you're not a bot"). */
    class BotCheck(cause: Throwable? = null) :
        YouTubeError(
            "YouTube asked for a bot check. Playback needs a proof-of-origin token.",
            cause,
        )

    /** Age-gated, private, paid, region-blocked, deleted - unplayable, but only this one video. */
    class Unavailable(reason: String, cause: Throwable? = null) : YouTubeError(reason, cause)

    /** Network, timeout, rate limit, or YouTube changed its page shape and parsing broke. */
    class Failed(reason: String, cause: Throwable? = null) : YouTubeError(reason, cause)
}
