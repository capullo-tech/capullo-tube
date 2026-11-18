package tech.capullo.tubecapullo.data.history

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** A Last.fm answer (or a "not found"), keyed by method and normalized names, without the API key. */
@Entity(tableName = "lastfm_cache")
data class LastfmCacheEntity(
    @PrimaryKey val request: String,
    val body: String,
    val fetchedAtEpochMs: Long,
)

@Dao
interface LastfmCacheDao {
    @Query("SELECT * FROM lastfm_cache WHERE request = :request")
    suspend fun byRequest(request: String): LastfmCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LastfmCacheEntity)
}
