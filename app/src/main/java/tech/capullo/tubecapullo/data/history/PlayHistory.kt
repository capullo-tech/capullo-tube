package tech.capullo.tubecapullo.data.history

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import tech.capullo.tubecapullo.data.youtube.YtVideo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted play history, backing Home's Listen again row and the Library's History list. The
 * station does not read it: the radio is YouTube's Mixes, not a taste model.
 *
 * [record] runs once per play, from [tech.capullo.tubecapullo.station.StationEngine.onProgress],
 * when the playhead passes min(30 s, half the track) - the one place a track counts as "played".
 * The engine runs it off the station loop. A failed write must never stall the station, so
 * [record] swallows and logs. Read failures collapse to empty: a broken table hides a section, it
 * must not blank a screen.
 */
@Singleton
class PlayHistory @Inject constructor(private val dao: PlayHistoryDao) {

    /** Records one play of [video]: first play inserts, a replay increments the count. */
    suspend fun record(video: YtVideo) {
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

    /** Most recently played first, re-emitted on every recorded play. A read failure emits one
     *  empty list and ends the flow: a broken table empties History and Listen again, it must not
     *  crash Library or Home. */
    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<YtVideo>> = dao.observeRecent(limit)
        .map { rows -> rows.map { it.toYtVideo() } }
        .catch {
            Log.w(TAG, "play-history observe failed", it)
            emit(emptyList())
        }

    /** Listen again's pool ([PlayHistoryDao.observeListenAgainPool]): plays of [minPlays] or
     *  more, and the plays of liked songs and albums. A read failure emits one empty list. */
    fun observeListenAgainPool(minPlays: Int): Flow<List<PlayStat>> = dao.observeListenAgainPool(minPlays)
        .map { rows -> rows.map { PlayStat(it.toYtVideo(), it.playCount, it.lastPlayedAtEpochMs) } }
        .catch {
            Log.w(TAG, "play-history pool observe failed", it)
            emit(emptyList())
        }

    private fun PlayedTrackEntity.toYtVideo() = YtVideo(
        videoId = videoId,
        title = title,
        uploader = uploader,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl,
    )

    companion object {
        private const val TAG = "PlayHistory"

        /** Rows the Library's History list shows. GUESS, unmeasured: enough to scroll back
         *  through a few days of listening, small enough that the live query re-reading the whole
         *  page on every recorded play stays cheap. Too low hides last week; too high costs a
         *  bigger re-read per play for rows nobody scrolls to. */
        private const val RECENT_LIMIT = 100
    }
}

/** One played video with its counted plays and its last play: Listen again's input. */
data class PlayStat(val video: YtVideo, val playCount: Int, val lastPlayedAtEpochMs: Long)
