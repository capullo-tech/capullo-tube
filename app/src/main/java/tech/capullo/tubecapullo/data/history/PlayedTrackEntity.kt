package tech.capullo.tubecapullo.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

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
