package tech.capullo.tubecapullo.data.history

import androidx.room.Database
import androidx.room.RoomDatabase

/** v2: + liked_track (M-C). v1→v2 migration lives in [MIGRATION_1_2]; its CREATE TABLE is copied
 *  verbatim from the exported schema JSON so Room's runtime table check passes. */
@Database(
    entities = [PlayedTrackEntity::class, LikedTrackEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class TubeCapulloDatabase : RoomDatabase() {
    abstract fun playHistoryDao(): PlayHistoryDao

    abstract fun likedTrackDao(): LikedTrackDao

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
    }
}
