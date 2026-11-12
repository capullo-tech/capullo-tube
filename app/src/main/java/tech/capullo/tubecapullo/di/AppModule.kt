package tech.capullo.tubecapullo.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tech.capullo.tubecapullo.data.history.LikedAlbumDao
import tech.capullo.tubecapullo.data.history.LikedTrackDao
import tech.capullo.tubecapullo.data.history.LyricsDao
import tech.capullo.tubecapullo.data.history.PlayHistoryDao
import tech.capullo.tubecapullo.data.history.StationSnapshotDao
import tech.capullo.tubecapullo.data.history.TubeCapulloDatabase
import javax.inject.Singleton
import kotlin.random.Random

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** App-lifetime IO scope. Injected (rather than created inline) so JVM tests can hand the
     *  StationEngine a test scope and drive its Mix fetch and prefetch coroutines deterministically. */
    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Station randomness (shuffle, Liked radio's order of likes per round). Injected so JVM
     *  tests can seed it and assert exact orders. */
    @Provides
    @Singleton
    fun provideRandom(): Random = Random.Default

    /** Play-history store. One file, no singleton guard needed - Hilt scopes the instance.
     *  MIGRATION_1_2 adds liked_track, MIGRATION_2_3 adds play_event, MIGRATION_3_4 drops it,
     *  MIGRATION_4_5 adds liked_album and station_snapshot, MIGRATION_5_6 adds lyrics; none
     *  touches played_track (M-B rows survive) or, after it exists, liked_track. */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TubeCapulloDatabase = Room.databaseBuilder(context, TubeCapulloDatabase::class.java, "tubecapullo.db")
        .addMigrations(
            TubeCapulloDatabase.MIGRATION_1_2,
            TubeCapulloDatabase.MIGRATION_2_3,
            TubeCapulloDatabase.MIGRATION_3_4,
            TubeCapulloDatabase.MIGRATION_4_5,
            TubeCapulloDatabase.MIGRATION_5_6,
        )
        .build()

    @Provides
    @Singleton
    fun providePlayHistoryDao(db: TubeCapulloDatabase): PlayHistoryDao = db.playHistoryDao()

    @Provides
    @Singleton
    fun provideLikedTrackDao(db: TubeCapulloDatabase): LikedTrackDao = db.likedTrackDao()

    @Provides
    @Singleton
    fun provideLikedAlbumDao(db: TubeCapulloDatabase): LikedAlbumDao = db.likedAlbumDao()

    @Provides
    @Singleton
    fun provideStationSnapshotDao(db: TubeCapulloDatabase): StationSnapshotDao = db.stationSnapshotDao()

    @Provides
    @Singleton
    fun provideLyricsDao(db: TubeCapulloDatabase): LyricsDao = db.lyricsDao()
}
