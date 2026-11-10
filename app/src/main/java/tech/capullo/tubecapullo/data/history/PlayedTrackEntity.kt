package tech.capullo.tubecapullo.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per video ever played, updated in place on replays - the "Because you played" home
 * section's source (M-B). [playCount] ranks replays first; [lastPlayedAtEpochMs] breaks ties and
 * keeps a single-play song visible. Metadata mirrors [tech.capullo.tubecapullo.data.youtube.YtVideo]
 * so cards render without a network call.
 */
@Entity(tableName = "played_track")
data class PlayedTrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val playCount: Int,
    val lastPlayedAtEpochMs: Long,
)
