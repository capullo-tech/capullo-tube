package tech.capullo.tubecapullo.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayHistoryDao {
    @Query("SELECT * FROM played_track WHERE videoId = :videoId")
    suspend fun byId(videoId: String): PlayedTrackEntity?

    @Transaction
    suspend fun record(entity: PlayedTrackEntity) {
        val existing = byId(entity.videoId)
        if (existing == null) {
            insert(entity)
        } else {
            update(
                existing.copy(
                    title = entity.title,
                    uploader = entity.uploader,
                    // A play without a length (a QuantumCast song the player never measured)
                    // keeps the length an earlier play stored.
                    durationSeconds = entity.durationSeconds.takeIf { it > 0 } ?: existing.durationSeconds,
                    thumbnailUrl = entity.thumbnailUrl,
                    playCount = existing.playCount + 1,
                    lastPlayedAtEpochMs = entity.lastPlayedAtEpochMs,
                ),
            )
        }
    }

    @Insert
    suspend fun insert(entity: PlayedTrackEntity)

    @Update
    suspend fun update(entity: PlayedTrackEntity)

    @Query("SELECT * FROM played_track ORDER BY lastPlayedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PlayedTrackEntity>>

    @Query(
        "SELECT * FROM played_track WHERE playCount >= :minPlays " +
            "OR videoId IN (SELECT videoId FROM liked_track) " +
            "OR videoId IN (SELECT videoId FROM liked_album)",
    )
    fun observeListenAgainPool(minPlays: Int): Flow<List<PlayedTrackEntity>>

    @Query(DELETE_UNLIKED_PLAYED_BEFORE)
    suspend fun deleteUnlikedPlayedBefore(cutoffEpochMs: Long): Int

    @Query(DELETE_ALL)
    suspend fun deleteAll(): Int

    @Query(DELETE_BY_ID)
    suspend fun delete(videoId: String): Int

    companion object {
        const val DELETE_UNLIKED_PLAYED_BEFORE = "DELETE FROM played_track WHERE lastPlayedAtEpochMs < :cutoffEpochMs " +
            "AND videoId NOT IN (SELECT videoId FROM liked_track) " +
            "AND videoId NOT IN (SELECT videoId FROM liked_album)"

        const val DELETE_ALL = "DELETE FROM played_track"

        const val DELETE_BY_ID = "DELETE FROM played_track WHERE videoId = :videoId"
    }
}
