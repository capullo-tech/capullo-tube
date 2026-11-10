package tech.capullo.tubecapullo.data.history

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.youtube.YtVideo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted play history, backing Home's "Because you played" section (M-B).
 *
 * Writes are fire-and-forget from [tech.capullo.tubecapullo.station.StationEngine.onQueueAdvanced]
 * - the one place a track becomes "played" (where playedIds is also recorded) - and a failed
 * write must never stall the station, so [record] swallows and logs. Reads are suspend and their
 * failures collapse to an empty list: a broken table hides the section, it must not blank Home.
 */
@Singleton
class PlayHistory @Inject constructor(
    private val dao: PlayHistoryDao,
    private val scope: CoroutineScope,
) {

    /** Records one play of [video]: first play inserts, a replay increments the count. */
    fun record(video: YtVideo) {
        scope.launch {
            runCatching {
                dao.record(
                    PlayedTrackEntity(
                        videoId = video.videoId,
                        title = video.title,
                        uploader = video.uploader,
                        durationSeconds = video.durationSeconds,
                        thumbnailUrl = video.thumbnailUrl,
                        playCount = 1,
                        lastPlayedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }.onFailure {
                Log.w(TAG, "play-history write failed", it)
            }
        }
    }

    /** Most-played first ([PlayHistoryDao.mostPlayed]); failures hide the section. */
    suspend fun mostPlayed(limit: Int = 10): List<YtVideo> = runCatching { dao.mostPlayed(limit).map { it.toYtVideo() } }
        .onFailure { Log.w(TAG, "play-history read failed", it) }
        .getOrDefault(emptyList())

    private fun PlayedTrackEntity.toYtVideo() = YtVideo(
        videoId = videoId,
        title = title,
        uploader = uploader,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl,
    )

    private companion object {
        const val TAG = "PlayHistory"
    }
}
