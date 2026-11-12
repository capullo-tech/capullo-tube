package tech.capullo.tubecapullo.data.history

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The v4 to v5 migration on a real SQLite (desktop sqlite-jdbc): a v4 database built from the
 * exported 4.json, with rows in liked_track and played_track, migrated by the app's own
 * [TubeCapulloDatabase.MIGRATION_4_5]. Every row must survive, and every v5 table must have the
 * columns 5.json lists, which is what Room checks when it opens the database (a mismatch crashes
 * the app at first use).
 */
class MigrationTest {

    /** Gradle runs unit tests in the module directory. */
    private val schemas = File("schemas/tech.capullo.tubecapullo.data.history.TubeCapulloDatabase")

    private fun entities(version: Int): List<JsonObject> = Json.parseToJsonElement(File(schemas, "$version.json").readText())
        .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    /** A [SupportSQLiteDatabase] whose execSQL runs on [connection]: all a migration calls. */
    private fun database(connection: Connection): SupportSQLiteDatabase = mockk {
        every { execSQL(any<String>()) } answers { connection.createStatement().use { it.execute(firstArg()) } }
    }

    private fun Connection.rows(sql: String): List<List<Any?>> = createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            val columns = result.metaData.columnCount
            buildList {
                while (result.next()) add((1..columns).map { result.getObject(it) })
            }
        }
    }

    @Test
    fun `4 to 5 keeps every liked and played row and adds the v5 tables as Room expects them`() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            entities(4).forEach { entity ->
                val sql = entity.text("createSql").replace("\${TABLE_NAME}", entity.text("tableName"))
                db.createStatement().use { it.execute(sql) }
            }
            db.createStatement().use {
                it.execute(
                    "INSERT INTO liked_track VALUES " +
                        "('dQw4w9WgXcQ', 'Never Gonna', 'Rick Astley', 213, 'https://i/1', 1000), " +
                        "('9bZkp7q19f0', 'Gangnam Style', NULL, 252, NULL, 2000)",
                )
                it.execute(
                    "INSERT INTO played_track VALUES " +
                        "('dQw4w9WgXcQ', 'Never Gonna', 'Rick Astley', 213, 'https://i/1', 7, 5000), " +
                        "('kJQP7kiw5Fk', 'Despacito', 'Luis Fonsi', 282, NULL, 1, 6000)",
                )
            }
            val liked = db.rows("SELECT * FROM liked_track ORDER BY videoId")
            val played = db.rows("SELECT * FROM played_track ORDER BY videoId")

            TubeCapulloDatabase.MIGRATION_4_5.migrate(database(db))

            assertEquals(liked, db.rows("SELECT * FROM liked_track ORDER BY videoId"))
            assertEquals(played, db.rows("SELECT * FROM played_track ORDER BY videoId"))
            entities(5).forEach { entity ->
                val table = entity.text("tableName")
                val keys = entity.getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray
                    .map { it.jsonPrimitive.content }
                val expected = entity.getValue("fields").jsonArray.map { it.jsonObject }.map { field ->
                    val column = field.text("columnName")
                    listOf(
                        column,
                        field.text("affinity"),
                        field["notNull"]?.jsonPrimitive?.boolean ?: false,
                        keys.indexOf(column) + 1,
                    )
                }.sortedBy { it[0] as String }
                // PRAGMA table_info: cid, name, type, notnull, dflt_value, pk.
                val actual = db.rows("PRAGMA table_info(`$table`)")
                    .map { listOf(it[1], it[2], (it[3] as Number).toInt() == 1, (it[5] as Number).toInt()) }
                    .sortedBy { it[0] as String }
                assertEquals("table $table", expected, actual)
            }
            db.createStatement().use {
                it.execute("INSERT INTO liked_album VALUES ('AAAAAAAAAAA', 'Album', NULL, 2700, NULL, 1)")
                it.execute("INSERT INTO station_snapshot VALUES (1, 2, '{}')")
            }
        }
    }
}
