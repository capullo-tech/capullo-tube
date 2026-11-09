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
