package tech.capullo.tubecapullo.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LikedTrackDao {

    @Query("SELECT * FROM liked_track")
    suspend fun all(): List<LikedTrackEntity>

    /** REPLACE, not upsert-merge: a re-like just refreshes the row (likedAt, metadata). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LikedTrackEntity)

    @Query("DELETE FROM liked_track WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}
