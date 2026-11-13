package tech.capullo.tubecapullo.data.history

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A liked album: a long video the heart saved in album mode (PLAN "Home v2 and albums"). Its own
 * table, not a kind column on liked_track: every reader of liked_track (Liked songs, Liked radio,
 * the "More like" seeds, Listen again's song rule, the backup's song list) then stays album-free
 * with no filter to forget, and a song liked in a radio stays a song even when it is long. The
 * columns mirror [LikedTrackEntity], so a card renders without a network call; the chapters are
 * fetched again when the album plays.
 */
@Entity(tableName = "liked_album")
data class LikedAlbumEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val likedAtEpochMs: Long,
)
