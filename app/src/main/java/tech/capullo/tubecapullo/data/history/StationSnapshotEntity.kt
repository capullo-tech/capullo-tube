package tech.capullo.tubecapullo.data.history

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "station_snapshot")
data class StationSnapshotEntity(
    @PrimaryKey val id: Long,
    val savedAtEpochMs: Long,
    val state: String,
)

@Dao
interface StationSnapshotDao {
    @Query("SELECT * FROM station_snapshot ORDER BY savedAtEpochMs DESC")
    suspend fun all(): List<StationSnapshotEntity>

    @Query("SELECT * FROM station_snapshot ORDER BY savedAtEpochMs DESC")
    fun observe(): Flow<List<StationSnapshotEntity>>

    @Query("SELECT * FROM station_snapshot WHERE id = :id")
    suspend fun byId(id: Long): StationSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: StationSnapshotEntity)

    @Query(
        "DELETE FROM station_snapshot WHERE id NOT IN " +
            "(SELECT id FROM station_snapshot ORDER BY savedAtEpochMs DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Transaction
    suspend fun save(entity: StationSnapshotEntity, keep: Int) {
        upsert(entity)
        trim(keep)
    }

    @Query(DELETE_BY_ID)
    suspend fun delete(id: Long): Int

    @Query(DELETE_ALL)
    suspend fun deleteAll(): Int

    companion object {
        const val DELETE_BY_ID = "DELETE FROM station_snapshot WHERE id = :id"

        const val DELETE_ALL = "DELETE FROM station_snapshot"
    }
}
