package tech.capullo.tubecapullo.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LikedTrackDao {
    @Query("SELECT * FROM liked_track ORDER BY likedAtEpochMs DESC")
    suspend fun all(): List<LikedTrackEntity>

    @Query("SELECT * FROM liked_track ORDER BY likedAtEpochMs DESC")
    fun observe(): Flow<List<LikedTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LikedTrackEntity)

    @Query("DELETE FROM liked_track WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("SELECT videoId FROM liked_track")
    suspend fun ids(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringExisting(entities: List<LikedTrackEntity>): List<Long>
}
