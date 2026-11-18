package tech.capullo.tubecapullo.data.similar

import tech.capullo.tubecapullo.data.history.ResolvedTrackDao
import tech.capullo.tubecapullo.data.history.ResolvedTrackEntity
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtVideo
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** [searched] is true when this lookup cost a YouTube search; cached answers cost nothing. */
data class Resolved(val video: YtVideo?, val searched: Boolean)

/**
 * Turns a Last.fm name into a YouTube video. YouTube searches are the scarce resource (bot
 * checks), so every answer, found or not, is kept in Room and reused.
 */
@Singleton
class TrackResolver @Inject constructor(
    private val extractor: YouTubeExtractorClient,
    private val dao: ResolvedTrackDao,
) {
    internal var now: () -> Long = System::currentTimeMillis

    suspend fun resolve(artist: String, title: String): Resolved {
        val key = nameKey(artist, title)
        dao.byKey(key)?.takeIf { fresh(it) }?.let { return Resolved(it.toVideo(), searched = false) }
        // A failed search (network, bot check) is not an answer: nothing is cached.
        val results = extractor.search("$artist $title", limit = RESOLVE_SEARCH_RESULTS)
        val video = pickResolution(results, artist, title)
        dao.upsert(
            ResolvedTrackEntity(
                nameKey = key,
                videoId = video?.videoId,
                title = video?.title,
                uploader = video?.uploader,
                durationSeconds = video?.durationSeconds ?: 0,
                thumbnailUrl = video?.thumbnailUrl,
                resolvedAtEpochMs = now(),
            ),
        )
        return Resolved(video, searched = true)
    }

    private fun fresh(row: ResolvedTrackEntity): Boolean {
        val days = if (row.videoId != null) RESOLVE_HIT_DAYS else RESOLVE_MISS_DAYS
        return now() - row.resolvedAtEpochMs < TimeUnit.DAYS.toMillis(days)
    }

    private fun ResolvedTrackEntity.toVideo(): YtVideo? = videoId?.let {
        YtVideo(it, title.orEmpty(), uploader, durationSeconds, thumbnailUrl)
    }

    internal companion object {
        /** The probe found the right upload in the top 5 for 94 % of names. */
        const val RESOLVE_SEARCH_RESULTS = 5

        /** GUESS: uploads rarely move; a dead one fails playback and is skipped anyway. */
        const val RESOLVE_HIT_DAYS = 180L

        /** GUESS: a song missing today may be uploaded later. */
        const val RESOLVE_MISS_DAYS = 30L

        fun nameKey(artist: String, title: String): String = SeedNames.key(SeedNames.firstArtist(artist)) + "|" + SeedNames.key(title)
    }
}
