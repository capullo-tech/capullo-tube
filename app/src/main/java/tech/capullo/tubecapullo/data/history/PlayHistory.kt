package tech.capullo.tubecapullo.data.history

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import tech.capullo.tubecapullo.data.settings.HistoryRetention
import tech.capullo.tubecapullo.data.similar.LastfmClient
import tech.capullo.tubecapullo.data.similar.TrackResolver
import tech.capullo.tubecapullo.data.youtube.YtVideo
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayHistory @Inject constructor(
    private val dao: PlayHistoryDao,
    private val lyricsDao: LyricsDao,
    private val lastfmCache: LastfmCacheDao,
    private val resolvedTracks: ResolvedTrackDao,
) {
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
        expireSimilarCaches(nowEpochMs)
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
            // Last.fm answers are keyed by the songs radios started from: part of the history.
            val similar = lastfmCache.deleteAll() + resolvedTracks.deleteAll()
            Log.i(TAG, "play-history clear removed=$removed similar=$similar")
        }.onFailure {
            Log.w(TAG, "play-history clear failed", it)
        }
    }

    /** Expired Last.fm and YouTube-lookup rows are never read again; drop them whatever the retention. */
    private suspend fun expireSimilarCaches(nowEpochMs: Long) {
        runCatching {
            val days = TimeUnit.DAYS::toMillis
            val answers = lastfmCache.deleteFetchedBefore(nowEpochMs - days(LastfmClient.CACHE_DAYS))
            val lookups = resolvedTracks.deleteExpired(
                missCutoff = nowEpochMs - days(TrackResolver.RESOLVE_MISS_DAYS),
                hitCutoff = nowEpochMs - days(TrackResolver.RESOLVE_HIT_DAYS),
            )
            Log.i(TAG, "similar-cache expire answers=$answers lookups=$lookups")
        }.onFailure {
            Log.w(TAG, "similar-cache expire failed", it)
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
