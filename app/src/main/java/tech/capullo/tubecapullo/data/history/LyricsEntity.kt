package tech.capullo.tubecapullo.data.history

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val videoId: String,
    val kind: String,
    val source: String?,
    val text: String?,
    val fetchedAtEpochMs: Long,
    @ColumnInfo(defaultValue = "0") val offsetMs: Long = 0,
)

@Dao
interface LyricsDao {

    @Query("SELECT * FROM lyrics WHERE videoId = :videoId")
    suspend fun byId(videoId: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsEntity)

    @Query("UPDATE lyrics SET offsetMs = :offsetMs WHERE videoId = :videoId")
    suspend fun setOffset(videoId: String, offsetMs: Long)

    @Query("DELETE FROM lyrics WHERE videoId != :keep AND videoId NOT IN (SELECT videoId FROM played_track)")
    suspend fun deleteUnplayed(keep: String)
}
