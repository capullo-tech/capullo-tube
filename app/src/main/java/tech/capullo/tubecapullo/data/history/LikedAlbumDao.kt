package tech.capullo.tubecapullo.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** [LikedTrackDao]'s queries for liked_album: the same order and conflict rules. */
@Dao
interface LikedAlbumDao {

    @Query("SELECT * FROM liked_album ORDER BY likedAtEpochMs DESC")
    suspend fun all(): List<LikedAlbumEntity>

    @Query("SELECT * FROM liked_album ORDER BY likedAtEpochMs DESC")
    fun observe(): Flow<List<LikedAlbumEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LikedAlbumEntity)

    @Query("DELETE FROM liked_album WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("SELECT videoId FROM liked_album")
    suspend fun ids(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringExisting(entities: List<LikedAlbumEntity>): List<Long>
}
