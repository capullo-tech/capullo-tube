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
class LikedAlbums @Inject constructor(
    private val dao: LikedAlbumDao,
    private val scope: CoroutineScope,
) {
    fun set(album: YtVideo, liked: Boolean) {
        scope.launch {
            runCatching {
                if (liked) dao.insert(album.toEntity(System.currentTimeMillis())) else dao.delete(album.videoId)
            }.onFailure { Log.w(TAG, "liked-album write failed", it) }
        }
    }

    suspend fun all(): List<YtVideo> = runCatching { dao.all().map { it.toYtVideo() } }
        .onFailure { Log.w(TAG, "liked-album read failed", it) }
        .getOrDefault(emptyList())

    fun observeEntries(): Flow<List<LikedEntry>> = dao.observe()
        .map { rows -> rows.map { LikedEntry(it.toYtVideo(), it.likedAtEpochMs) } }
        .catch {
            Log.w(TAG, "liked-album observe failed", it)
            emit(emptyList())
        }

    suspend fun entries(): List<LikedEntry> = dao.all().map { LikedEntry(it.toYtVideo(), it.likedAtEpochMs) }

    suspend fun likedIds(): Set<String> = dao.ids().toSet()

    suspend fun importAll(entries: List<LikedEntry>): List<YtVideo> {
        if (entries.isEmpty()) return emptyList()
        val rowIds = dao.insertAllIgnoringExisting(entries.map { it.video.toEntity(it.likedAtEpochMs) })
        return entries.zip(rowIds).filter { (_, rowId) -> rowId != -1L }.map { it.first.video }
    }

    private fun YtVideo.toEntity(likedAt: Long) = LikedAlbumEntity(
        videoId = videoId,
        title = title,
        uploader = uploader,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl,
        likedAtEpochMs = likedAt,
    )

    private fun LikedAlbumEntity.toYtVideo() = YtVideo(
        videoId = videoId,
        title = title,
        uploader = uploader,
        durationSeconds = durationSeconds,
        thumbnailUrl = thumbnailUrl,
    )

    private companion object {
        const val TAG = "LikedAlbums"
    }
}
