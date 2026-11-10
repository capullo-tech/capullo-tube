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
import tech.capullo.tubecapullo.data.history.LikedTrackDao
import tech.capullo.tubecapullo.data.history.PlayHistoryDao
import tech.capullo.tubecapullo.data.history.TubeCapulloDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** App-lifetime IO scope. Injected (rather than created inline) so JVM tests can hand the
     *  StationEngine a test scope and drive its refill/prefetch coroutines deterministically. */
    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Play-history store. One file, no singleton guard needed - Hilt scopes the instance.
     *  MIGRATION_1_2 adds liked_track without touching played_track (M-B rows survive). */
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TubeCapulloDatabase = Room.databaseBuilder(context, TubeCapulloDatabase::class.java, "tubecapullo.db")
        .addMigrations(TubeCapulloDatabase.MIGRATION_1_2)
        .build()

    @Provides
    @Singleton
    fun providePlayHistoryDao(db: TubeCapulloDatabase): PlayHistoryDao = db.playHistoryDao()

    @Provides
    @Singleton
    fun provideLikedTrackDao(db: TubeCapulloDatabase): LikedTrackDao = db.likedTrackDao()
}
