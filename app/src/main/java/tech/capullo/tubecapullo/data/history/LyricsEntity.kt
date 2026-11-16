package tech.capullo.tubecapullo.data.history

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
)

@Dao
interface LyricsDao {

    @Query("SELECT * FROM lyrics WHERE videoId = :videoId")
    suspend fun byId(videoId: String): LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LyricsEntity)
}
