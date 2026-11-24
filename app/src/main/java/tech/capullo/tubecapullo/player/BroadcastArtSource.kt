package tech.capullo.tubecapullo.player

import tech.capullo.tubecapullo.data.youtube.YtVideo

/**
 * Where the broadcast's art for one video comes from. Web players show it in a square box cropped
 * to fill (`object-fit: cover`), so the 16:9 `maxresdefault` (1280x720, no letterbox) is the best
 * picture; some videos don't have one (404). The extractor's own thumbnail is often a 336 px crop,
 * and `hqdefault` (480x360, black bars on 16:9 videos) exists for every video.
 */
internal data class BroadcastArtSource(
    /** Announced as `artUrl` until a downloaded image is published: one that exists for every video. */
    val directUrl: String,
    /** Tried in order; the first image that downloads is published. */
    val downloadUrls: List<String>,
) {
    companion object {
        fun of(video: YtVideo): BroadcastArtSource {
            val hq = thumbnail(video.videoId, "hqdefault")
            val direct = video.thumbnailUrl ?: hq
            return BroadcastArtSource(direct, listOf(thumbnail(video.videoId, "maxresdefault"), direct, hq).distinct())
        }

        private fun thumbnail(videoId: String, name: String) = "https://i.ytimg.com/vi/$videoId/$name.jpg"
    }
}
