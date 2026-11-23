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
import okhttp3.OkHttpClient
import tech.capullo.audio.tunnel.TunnelManager
import tech.capullo.tubecapullo.BuildConfig
import tech.capullo.tubecapullo.data.history.LastfmCacheDao
import tech.capullo.tubecapullo.data.history.LikedAlbumDao
import tech.capullo.tubecapullo.data.history.LikedTrackDao
import tech.capullo.tubecapullo.data.history.LyricsDao
import tech.capullo.tubecapullo.data.history.PlayHistoryDao
import tech.capullo.tubecapullo.data.history.ResolvedTrackDao
import tech.capullo.tubecapullo.data.history.StationSnapshotDao
import tech.capullo.tubecapullo.data.history.TubeCapulloDatabase
import tech.capullo.tubecapullo.data.similar.LastfmSimilarFinder
import javax.inject.Named
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
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TubeCapulloDatabase = Room.databaseBuilder(context, TubeCapulloDatabase::class.java, "tubecapullo.db")
        .addMigrations(
            TubeCapulloDatabase.MIGRATION_1_2,
            TubeCapulloDatabase.MIGRATION_2_3,
            TubeCapulloDatabase.MIGRATION_3_4,
            TubeCapulloDatabase.MIGRATION_4_5,
            TubeCapulloDatabase.MIGRATION_5_6,
            TubeCapulloDatabase.MIGRATION_6_7,
            TubeCapulloDatabase.MIGRATION_7_8,
            TubeCapulloDatabase.MIGRATION_8_9,
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

    @Provides
    @Singleton
    fun provideResolvedTrackDao(db: TubeCapulloDatabase): ResolvedTrackDao = db.resolvedTrackDao()

    @Provides
    @Singleton
    fun provideLastfmCacheDao(db: TubeCapulloDatabase): LastfmCacheDao = db.lastfmCacheDao()

    @Provides
    @Named(LastfmSimilarFinder.BUILT_IN_KEY)
    fun provideLastfmApiKey(): String = BuildConfig.LASTFM_API_KEY

    // TunnelManager carries no DI annotations; @Singleton keeps it to one cloudflared process.
    @Provides
    @Singleton
    fun provideTunnelManager(@ApplicationContext context: Context): TunnelManager = TunnelManager(context)
}
