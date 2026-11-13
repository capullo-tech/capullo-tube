package tech.capullo.tubecapullo.data.history

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.youtube.YtVideo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LikedTracks @Inject constructor(
    private val dao: LikedTrackDao,
    private val scope: CoroutineScope,
) {
    fun set(video: YtVideo, liked: Boolean) {
        scope.launch {
            runCatching {
                if (liked) {
                    dao.insert(
                        LikedTrackEntity(
                            videoId = video.videoId,
                            title = video.title,
                            uploader = video.uploader,
                            durationSeconds = video.durationSeconds,
                            thumbnailUrl = video.thumbnailUrl,
                            likedAtEpochMs = System.currentTimeMillis(),
                        ),
                    )
                } else {
                    dao.delete(video.videoId)
                }
            }.onFailure {
                Log.w(TAG, "liked-track write failed", it)
            }
        }
    }

    suspend fun all(): List<YtVideo> = runCatching { dao.all().map { it.toYtVideo() } }
        .onFailure { Log.w(TAG, "liked-track read failed", it) }
        .getOrDefault(emptyList())

    fun observe(): Flow<List<YtVideo>> = dao.observe()
        .map { rows -> rows.map { it.toYtVideo() } }
        .catch {
            Log.w(TAG, "liked-track observe failed", it)
            emit(emptyList())
        }

    fun observeEntries(): Flow<List<LikedEntry>> = dao.observe()
        .map { rows -> rows.map { LikedEntry(it.toYtVideo(), it.likedAtEpochMs) } }
        .catch {
            Log.w(TAG, "liked-track observe failed", it)
            emit(emptyList())
        }

    suspend fun entries(): List<LikedEntry> = dao.all().map { LikedEntry(it.toYtVideo(), it.likedAtEpochMs) }

    suspend fun likedIds(): Set<String> = dao.ids().toSet()

    suspend fun importAll(entries: List<LikedEntry>): List<YtVideo> {
        if (entries.isEmpty()) return emptyList()
        val rowIds = dao.insertAllIgnoringExisting(
            entries.map { (video, likedAt) ->
                LikedTrackEntity(
                    videoId = video.videoId,
                    title = video.title,
                    uploader = video.uploader,
                    durationSeconds = video.durationSeconds,
                    thumbnailUrl = video.thumbnailUrl,
                    likedAtEpochMs = likedAt,
                )
            },
        )
        return entries.zip(rowIds).filter { (_, rowId) -> rowId != -1L }.map { it.first.video }
    }

    private fun LikedTrackEntity.toYtVideo() = YtVideo(
        videoId = videoId,
        title = title,
        uploader = uploader,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl,
    )

    private companion object {
        const val TAG = "LikedTracks"
    }
}

data class LikedEntry(val video: YtVideo, val likedAtEpochMs: Long)
