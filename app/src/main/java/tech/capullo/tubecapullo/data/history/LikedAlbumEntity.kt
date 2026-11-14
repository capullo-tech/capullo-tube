package tech.capullo.tubecapullo.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "liked_album")
data class LikedAlbumEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val likedAtEpochMs: Long,
)
