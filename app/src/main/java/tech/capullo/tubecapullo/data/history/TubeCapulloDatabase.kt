package tech.capullo.tubecapullo.data.history

import androidx.room.Database
import androidx.room.RoomDatabase

/** v2: + liked_track (M-C). v3: + play_event (item 13, fatigue). v4: - play_event (the radio no
 *  longer reads history). v5: + liked_album, + station_snapshot (Home v2: album likes, Continue
 *  and Previous across restarts). v6: + lyrics (the lyrics cache). Each migration's CREATE TABLE
 *  matches the exported schema JSON so Room's runtime table check passes. */
@Database(
    entities = [
        PlayedTrackEntity::class,
        LikedTrackEntity::class,
        LikedAlbumEntity::class,
        StationSnapshotEntity::class,
        LyricsEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class TubeCapulloDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun likedTrackDao(): LikedTrackDao

    abstract fun likedAlbumDao(): LikedAlbumDao

    abstract fun stationSnapshotDao(): StationSnapshotDao

    abstract fun lyricsDao(): LyricsDao

    companion object {
        /** CREATE TABLE copied verbatim from the exported 2.json (Room validates table info at
         *  open; a hand-typed variant that drifts crashes the app on first DB use). */
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

        /**
         * Adds play_event only (the v3 schema, 3.json); played_track and liked_track rows are
         * untouched. A v2 install upgrades through this and then [MIGRATION_3_4], which drops the
         * table again: Room needs an unbroken migration path.
         */
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

        /**
         * Drops play_event: its only reader was the radio's fatigue term, gone with the scored
         * radio. played_track (History, Listen again) and liked_track rows are untouched.
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `play_event`")
            }
        }

        /**
         * Adds liked_album and station_snapshot (the v5 schema, 5.json, CREATE TABLE copied
         * verbatim); played_track and liked_track rows are untouched. MigrationTest runs it on
         * a real SQLite.
         */
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

        /**
         * Adds lyrics (the v6 schema, 6.json, CREATE TABLE copied verbatim); every other table
         * and its rows are untouched. MigrationTest runs it on a real SQLite.
         */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lyrics` (`videoId` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `source` TEXT, `text` TEXT, " +
                        "`fetchedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`videoId`))",
                )
            }
        }
    }
}
