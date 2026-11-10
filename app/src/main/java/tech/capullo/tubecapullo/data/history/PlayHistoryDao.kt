package tech.capullo.tubecapullo.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface PlayHistoryDao {

    @Query("SELECT * FROM played_track WHERE videoId = :videoId")
    suspend fun byId(videoId: String): PlayedTrackEntity?

    /**
     * First play inserts (count 1); a replay increments the count and refreshes the metadata
     * (title/uploader/art can change upstream). NOT @Upsert: REPLACE would silently reset
     * [PlayedTrackEntity.playCount] and lose exactly the signal this table exists for.
     */
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

    /** Replays rank first, recency breaks ties among single plays. */
    @Query(
        "SELECT * FROM played_track ORDER BY playCount DESC, lastPlayedAtEpochMs DESC LIMIT :limit",
    )
    suspend fun mostPlayed(limit: Int): List<PlayedTrackEntity>
}
