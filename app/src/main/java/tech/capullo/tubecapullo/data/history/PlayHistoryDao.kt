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
                    durationSeconds = entity.durationSeconds,
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
}
