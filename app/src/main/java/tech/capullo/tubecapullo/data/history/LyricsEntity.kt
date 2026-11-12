package tech.capullo.tubecapullo.data.history

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * One video's lyrics as the cache keeps them (tech.capullo.tubecapullo.data.lyrics
 * .LyricsRepository): what the lookup found, or that it found nothing. [kind] is "synced" (LRC
 * [text]), "plain" (plain [text]) or "none" ([text] and [source] null). [source] is the
 * LyricsSource log name ("lrclib-get", "lrclib-search", "description").
 */
@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val videoId: String,
    val kind: String,
    val source: String?,
    val text: String?,
    val fetchedAtEpochMs: Long,
)

@Dao
interface LyricsDao {

    @Query("SELECT * FROM lyrics WHERE videoId = :videoId")
    suspend fun byId(videoId: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsEntity)
}
