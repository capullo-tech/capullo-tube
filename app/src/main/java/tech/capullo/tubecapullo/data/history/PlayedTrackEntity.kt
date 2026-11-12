package tech.capullo.tubecapullo.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per video ever played, updated in place on replays - the source of Home's Listen again
 * row and the Library's History list (most recent [lastPlayedAtEpochMs] first). [playCount] counts
 * every counted play; Listen again's "played 3+ times" rule reads it.
 * Metadata mirrors [tech.capullo.tubecapullo.data.youtube.YtVideo] so cards render without a
 * network call.
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
