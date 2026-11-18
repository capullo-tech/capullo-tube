package tech.capullo.tubecapullo.data.history

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlayedTrackEntity::class,
        LikedTrackEntity::class,
        LikedAlbumEntity::class,
        StationSnapshotEntity::class,
        LyricsEntity::class,
        ResolvedTrackEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class TubeCapulloDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun likedTrackDao(): LikedTrackDao

    abstract fun likedAlbumDao(): LikedAlbumDao

    abstract fun stationSnapshotDao(): StationSnapshotDao

    abstract fun lyricsDao(): LyricsDao

    abstract fun resolvedTrackDao(): ResolvedTrackDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `liked_track` (`videoId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `uploader` TEXT, `durationSeconds` INTEGER NOT NULL, " +
                        "`thumbnailUrl` TEXT, `likedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`videoId`))",
                )
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `play_event` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`videoId` TEXT NOT NULL, `uploader` TEXT, " +
                        "`playedAtEpochMs` INTEGER NOT NULL)",
                )
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `play_event`")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `liked_album` (`videoId` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, `uploader` TEXT, `durationSeconds` INTEGER NOT NULL, " +
                        "`thumbnailUrl` TEXT, `likedAtEpochMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`videoId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `station_snapshot` (`id` INTEGER NOT NULL, " +
                        "`savedAtEpochMs` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))",
                )
            }
        }

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lyrics` (`videoId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `source` TEXT, `text` TEXT, " +
                        "`fetchedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`videoId`))",
                )
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `lyrics` ADD COLUMN `offsetMs` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DELETE FROM `lyrics` WHERE `videoId` NOT IN (SELECT `videoId` FROM `played_track`)")
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `resolved_track` (`nameKey` TEXT NOT NULL, " +
                        "`videoId` TEXT, `title` TEXT, `uploader` TEXT, " +
                        "`durationSeconds` INTEGER NOT NULL, `thumbnailUrl` TEXT, " +
                        "`resolvedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`nameKey`))",
                )
            }
        }
    }
}
