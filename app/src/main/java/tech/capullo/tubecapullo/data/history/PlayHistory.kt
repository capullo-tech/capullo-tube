package tech.capullo.tubecapullo.data.history

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import tech.capullo.tubecapullo.data.settings.HistoryRetention
import tech.capullo.tubecapullo.data.youtube.YtVideo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayHistory @Inject constructor(private val dao: PlayHistoryDao, private val lyricsDao: LyricsDao) {
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

    suspend fun prune(retention: HistoryRetention, keepLyricsOf: String?, nowEpochMs: Long = System.currentTimeMillis()) {
        val cutoff = retention.cutoffEpochMs(nowEpochMs) ?: return
        runCatching {
            val removed = dao.deleteUnlikedPlayedBefore(cutoff)
            lyricsDao.deleteUnplayed(keepLyricsOf.orEmpty())
            Log.i(TAG, "play-history prune retention=$retention cutoff=$cutoff removed=$removed")
        }.onFailure {
            Log.w(TAG, "play-history prune failed", it)
        }
    }

    suspend fun clear(keepLyricsOf: String?) {
        runCatching {
            val removed = dao.deleteAll()
            lyricsDao.deleteUnplayed(keepLyricsOf.orEmpty())
            Log.i(TAG, "play-history clear removed=$removed")
        }.onFailure {
            Log.w(TAG, "play-history clear failed", it)
        }
    }

    suspend fun remove(videoId: String, keepLyricsOf: String?) {
        runCatching {
            val removed = dao.delete(videoId)
            lyricsDao.deleteUnplayed(keepLyricsOf.orEmpty())
            Log.i(TAG, "play-history remove id=$videoId removed=$removed")
        }.onFailure {
            Log.w(TAG, "play-history remove id=$videoId failed", it)
        }
    }

    fun observeRecent(limit: Int = RECENT_LIMIT): Flow<List<YtVideo>> = dao.observeRecent(limit)
        .map { rows -> rows.map { it.toYtVideo() } }
        .catch {
            Log.w(TAG, "play-history observe failed", it)
            emit(emptyList())
        }

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

        private const val RECENT_LIMIT = 100
    }
}

data class PlayStat(val video: YtVideo, val playCount: Int, val lastPlayedAtEpochMs: Long)
