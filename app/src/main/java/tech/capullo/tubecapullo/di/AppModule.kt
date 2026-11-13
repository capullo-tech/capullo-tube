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
import tech.capullo.tubecapullo.data.history.PlayHistoryDao
import tech.capullo.tubecapullo.data.history.StationSnapshotDao
import tech.capullo.tubecapullo.data.history.TubeCapulloDatabase
import javax.inject.Singleton
import kotlin.random.Random

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideRandom(): Random = Random.Default

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TubeCapulloDatabase = Room.databaseBuilder(context, TubeCapulloDatabase::class.java, "tubecapullo.db")
        .addMigrations(
            TubeCapulloDatabase.MIGRATION_1_2,
            TubeCapulloDatabase.MIGRATION_2_3,
            TubeCapulloDatabase.MIGRATION_3_4,
            TubeCapulloDatabase.MIGRATION_4_5,
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
}
