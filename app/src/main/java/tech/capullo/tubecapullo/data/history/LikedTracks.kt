package tech.capullo.tubecapullo.data.history

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tech.capullo.tubecapullo.data.youtube.YtVideo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted likes (M-C): the heart state and the like half of the taste signal survive restarts.
 *
 * Writes are fire-and-forget from [tech.capullo.tubecapullo.station.StationEngine.toggleLike] -
 * a failed write must never break liking as a station signal, so [set] swallows and logs. Reads
 * are suspend and hydrate the engine once at startup.
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

    /** All liked tracks; failures collapse to empty (the session then starts taste-cold). */
    suspend fun all(): List<YtVideo> = runCatching { dao.all().map { it.toYtVideo() } }
        .onFailure { Log.w(TAG, "liked-track read failed", it) }
        .getOrDefault(emptyList())

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
