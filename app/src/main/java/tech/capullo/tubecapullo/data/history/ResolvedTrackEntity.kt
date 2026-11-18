package tech.capullo.tubecapullo.data.history

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A Last.fm name looked up on YouTube. A row without [videoId] remembers that the search found
 * no matching upload, so the name is not searched again until the row expires.
 */
@Entity(tableName = "resolved_track")
data class ResolvedTrackEntity(
    @PrimaryKey val nameKey: String,
    val videoId: String?,
    val title: String?,
    val uploader: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val resolvedAtEpochMs: Long,
)

@Dao
interface ResolvedTrackDao {
    @Query("SELECT * FROM resolved_track WHERE nameKey = :nameKey")
    suspend fun byKey(nameKey: String): ResolvedTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ResolvedTrackEntity)

    @Query(DELETE_EXPIRED)
    suspend fun deleteExpired(missCutoff: Long, hitCutoff: Long): Int

    @Query(DELETE_ALL)
    suspend fun deleteAll(): Int

    companion object {
        const val DELETE_EXPIRED = "DELETE FROM resolved_track WHERE " +
            "(videoId IS NULL AND resolvedAtEpochMs < :missCutoff) OR resolvedAtEpochMs < :hitCutoff"
        const val DELETE_ALL = "DELETE FROM resolved_track"
    }
}
