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

    /**
     * One counted play: first play inserts (count 1); a replay increments the count and refreshes
     * the metadata (title/uploader/art can change upstream). NOT @Upsert: REPLACE would silently
     * reset [PlayedTrackEntity.playCount].
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

    /** Most recently played first, as a live query: Room re-emits after every record. */
    @Query("SELECT * FROM played_track ORDER BY lastPlayedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PlayedTrackEntity>>

    /** Listen again's pool, as a live query: songs played at least [minPlays] times, and every
     *  liked song or album that has played at all (its last play decides whether it is old). */
    @Query(
        "SELECT * FROM played_track WHERE playCount >= :minPlays " +
            "OR videoId IN (SELECT videoId FROM liked_track) " +
            "OR videoId IN (SELECT videoId FROM liked_album)",
    )
    fun observeListenAgainPool(minPlays: Int): Flow<List<PlayedTrackEntity>>
}
