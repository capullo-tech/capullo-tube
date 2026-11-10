package tech.capullo.tubecapullo.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A liked track (M-C: likes survive restarts). Persisting the uploader matters as much as the id:
 * [tech.capullo.tubecapullo.station.StationEngine]'s uploader nudge steers on likedUploaders, and
 * ids alone would drop that half of the taste signal on hydration. The rest of the metadata is
 * carried so a future "From your likes" home row is one query away.
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
