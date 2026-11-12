package tech.capullo.tubecapullo.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A liked track (M-C: likes survive restarts). The metadata is carried with the id so Liked,
 * Liked radio and the Library render and play a like without a network call.
 */
@Entity(tableName = "liked_track")
data class LikedTrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val likedAtEpochMs: Long,
)
