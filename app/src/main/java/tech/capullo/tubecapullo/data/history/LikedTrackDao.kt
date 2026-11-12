package tech.capullo.tubecapullo.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LikedTrackDao {

    /** Newest like first: a re-like REPLACEs the row with a fresh likedAt, so it moves to top. */
    @Query("SELECT * FROM liked_track ORDER BY likedAtEpochMs DESC")
    suspend fun all(): List<LikedTrackEntity>

    /** [all] as a live query: Room re-emits after every insert or delete on liked_track. */
    @Query("SELECT * FROM liked_track ORDER BY likedAtEpochMs DESC")
    fun observe(): Flow<List<LikedTrackEntity>>

    /** REPLACE, not upsert-merge: a re-like just refreshes the row (likedAt, metadata). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LikedTrackEntity)

    @Query("DELETE FROM liked_track WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    /** Every liked id, unordered: the "already liked" side of an import's merge. */
    @Query("SELECT videoId FROM liked_track")
    suspend fun ids(): List<String>

    /** Bulk import (backup restore), one transaction. IGNORE, not REPLACE: an id that is already
     *  liked keeps its own likedAt and metadata. One row id per entity, -1 where the id existed. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoringExisting(entities: List<LikedTrackEntity>): List<Long>
}
