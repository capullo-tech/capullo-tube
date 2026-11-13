package tech.capullo.tubecapullo.data.history

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import tech.capullo.tubecapullo.station.StationSnapshot
import tech.capullo.tubecapullo.station.StationSnapshotCodec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StationSnapshots @Inject constructor(private val dao: StationSnapshotDao) {
    suspend fun save(snapshot: StationSnapshot, keep: Int) {
        runCatching {
            dao.save(
                StationSnapshotEntity(
                    snapshot.id,
                    snapshot.savedAtEpochMs,
                    StationSnapshotCodec.encode(snapshot),
                ),
                keep,
            )
        }.onFailure { Log.w(TAG, "station-snapshot write failed", it) }
    }

    suspend fun all(): List<StationSnapshot> = runCatching { dao.all().mapNotNull { it.decode() } }
        .onFailure { Log.w(TAG, "station-snapshot read failed", it) }
        .getOrDefault(emptyList())

    suspend fun byId(id: Long): StationSnapshot? = runCatching { dao.byId(id)?.decode() }
        .onFailure { Log.w(TAG, "station-snapshot read failed", it) }
        .getOrNull()

    fun observe(): Flow<List<StationSnapshot>> = dao.observe()
        .map { rows -> rows.mapNotNull { it.decode() } }
        .catch {
            Log.w(TAG, "station-snapshot observe failed", it)
            emit(emptyList())
        }

    private fun StationSnapshotEntity.decode(): StationSnapshot? = StationSnapshotCodec.decode(state)
        ?: run {
            Log.w(TAG, "station-snapshot id=$id result=skipped reason=undecodable")
            null
        }

    private companion object {
        const val TAG = "StationSnapshots"
    }
}
