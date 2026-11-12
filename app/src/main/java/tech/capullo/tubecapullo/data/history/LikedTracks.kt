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

/**
 * Persisted likes (M-C): the heart state, Liked and Liked radio survive restarts.
 *
 * Writes are fire-and-forget from [tech.capullo.tubecapullo.station.StationEngine.toggleLike] -
 * a failed write must never break liking in the station, so [set] swallows and logs. [all]
 * hydrates the engine once at startup; [observe] backs the Library's Liked list. Both order
 * newest like first. [entries], [likedIds] and [importAll] serve the likes backup
 * (data/backup/) and throw, because there the user asked and must hear why it failed.
 */
@Singleton
class LikedTracks @Inject constructor(
    private val dao: LikedTrackDao,
    private val scope: CoroutineScope,
) {

    /** Records [video] as liked, or removes it when [liked] is false. */
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

    /** All liked tracks, newest like first; failures collapse to empty (the session then starts
     *  with no likes). */
    suspend fun all(): List<YtVideo> = runCatching { dao.all().map { it.toYtVideo() } }
        .onFailure { Log.w(TAG, "liked-track read failed", it) }
        .getOrDefault(emptyList())

    /** Liked tracks, newest like first, re-emitted on every like and unlike. A read failure emits
     *  one empty list and ends the flow: the Library shows no likes rather than crashing. */
    fun observe(): Flow<List<YtVideo>> = dao.observe()
        .map { rows -> rows.map { it.toYtVideo() } }
        .catch {
            Log.w(TAG, "liked-track observe failed", it)
            emit(emptyList())
        }

    /** [observe] with the like dates, for Listen again's age rule. */
    fun observeEntries(): Flow<List<LikedEntry>> = dao.observe()
        .map { rows -> rows.map { LikedEntry(it.toYtVideo(), it.likedAtEpochMs) } }
        .catch {
            Log.w(TAG, "liked-track observe failed", it)
            emit(emptyList())
        }

    /** All liked tracks with their like dates, newest like first, for a backup export. Throws on
     *  a read failure: the export must say why, not write an empty file. */
    suspend fun entries(): List<LikedEntry> = dao.all().map { LikedEntry(it.toYtVideo(), it.likedAtEpochMs) }

    /** Every liked id, for an import's "already liked" count. Throws on a read failure. */
    suspend fun likedIds(): Set<String> = dao.ids().toSet()

    /**
     * Bulk insert of imported likes with their explicit like dates. Merge only: an id that is
     * already liked keeps its row (likedAt and metadata). Returns the videos actually inserted, in
     * [entries] order. Throws on a write failure: unlike [set], an import is a user action whose
     * failure the user must see.
     */
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

/** A liked track with the time it was liked: the unit of a likes backup (export and import). */
data class LikedEntry(val video: YtVideo, val likedAtEpochMs: Long)
