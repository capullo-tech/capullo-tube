package tech.capullo.tubecapullo.data.history

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import tech.capullo.tubecapullo.data.settings.HistoryRetention
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

class HistoryDeletesTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private val dao = mockk<PlayHistoryDao> {
        coEvery { deleteUnlikedPlayedBefore(any()) } coAnswers {
            db.update(PlayHistoryDao.DELETE_UNLIKED_PLAYED_BEFORE, firstArg<Long>())
        }
        coEvery { deleteAll() } coAnswers { db.update(PlayHistoryDao.DELETE_ALL) }
        coEvery { delete(any()) } coAnswers { db.update(PlayHistoryDao.DELETE_BY_ID, firstArg<String>()) }
        coEvery { byId(any()) } coAnswers {
            val id = firstArg<String>()
            if (id == "creep") PlayedTrackEntity("creep", "Radiohead - Creep", "Radiohead", 239, null, 3, 1) else null
        }
    }

    private val lyricsDao = mockk<LyricsDao> {
        coEvery { deleteUnplayed(any()) } coAnswers {
            db.update(LyricsDao.DELETE_UNPLAYED, firstArg<String>())
            Unit
        }
    }

    private val lastfmCache = mockk<LastfmCacheDao> {
        coEvery { deleteFetchedBefore(any()) } coAnswers { db.update(LastfmCacheDao.DELETE_FETCHED_BEFORE, firstArg<Long>()) }
        coEvery { deleteAll() } coAnswers { db.update(LastfmCacheDao.DELETE_ALL) }
        coEvery { deleteRequests(any()) } coAnswers {
            firstArg<List<String>>().sumOf { db.update("DELETE FROM lastfm_cache WHERE request = :request", it) }
        }
    }

    private val resolvedTracks = mockk<ResolvedTrackDao> {
        coEvery { deleteExpired(any(), any()) } coAnswers {
            db.update(ResolvedTrackDao.DELETE_EXPIRED, firstArg<Long>(), secondArg<Long>())
        }
        coEvery { deleteAll() } coAnswers { db.update(ResolvedTrackDao.DELETE_ALL) }
    }

    private val history = PlayHistory(dao, lyricsDao, lastfmCache, resolvedTracks)

    private val untouched = listOf("liked_track", "liked_album", "station_snapshot")

    private val now = TimeUnit.DAYS.toMillis(1_000)

    private fun daysAgo(days: Long) = now - TimeUnit.DAYS.toMillis(days)

    private fun Connection.update(sql: String, vararg args: Any): Int = prepareStatement(sql.replace(Regex(":\\w+"), "?")).use { statement ->
        args.forEachIndexed { index, arg -> statement.setObject(index + 1, arg) }
        statement.executeUpdate()
    }

    private fun Connection.ids(table: String, column: String = "videoId"): List<Any> = createStatement().use { statement ->
        statement.executeQuery("SELECT $column FROM $table ORDER BY $column").use { result ->
            buildList { while (result.next()) add(result.getObject(1)) }
        }
    }

    private fun Connection.rows(table: String): List<List<Any?>> = createStatement().use { statement ->
        statement.executeQuery("SELECT * FROM $table ORDER BY 1").use { result ->
            val columns = result.metaData.columnCount
            buildList { while (result.next()) add((1..columns).map { result.getObject(it) }) }
        }
    }

    private fun snapshot(tables: List<String>) = tables.associateWith { db.rows(it) }

    private fun assertUnchanged(before: Map<String, List<List<Any?>>>) = before.forEach { (table, rows) ->
        assertEquals("table $table", rows, db.rows(table))
    }

    private fun played(id: String, lastPlayed: Long) = "INSERT INTO played_track VALUES ('$id', '$id', NULL, 200, NULL, 5, $lastPlayed)"

    private fun lyrics(id: String) = "INSERT INTO lyrics VALUES ('$id', 'plain', 'description', 'la', 1, 0)"

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        val schema = File("schemas/tech.capullo.tubecapullo.data.history.TubeCapulloDatabase/9.json")
        Json.parseToJsonElement(schema.readText()).jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
            .map { it.jsonObject }
            .forEach { entity ->
                val table = entity.getValue("tableName").jsonPrimitive.content
                val sql = entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)
                db.createStatement().use { it.execute(sql) }
            }
        db.createStatement().use { statement ->
            listOf(
                played("recent", daysAgo(10)),
                played("months", daysAgo(100)),
                played("ancient", daysAgo(400)),
                played("likedSong", daysAgo(400)),
                played("likedAlbum", daysAgo(400)),
                "INSERT INTO liked_track VALUES ('likedSong', 'likedSong', NULL, 200, NULL, 1)",
                "INSERT INTO liked_album VALUES ('likedAlbum', 'likedAlbum', NULL, 2700, NULL, 1)",
                "INSERT INTO liked_track VALUES ('neverPlayed', 'neverPlayed', NULL, 200, NULL, 1)",
                "INSERT INTO station_snapshot VALUES (1, 2, '{}'), (2, 3, '{}'), (3, 4, '{}')",
                lyrics("recent"),
                lyrics("months"),
                lyrics("ancient"),
                lyrics("likedSong"),
                lyrics("likedAlbum"),
                lyrics("playing"),
                lyrics("orphan"),
                "INSERT INTO lastfm_cache VALUES ('fresh', '{}', ${daysAgo(5)}), ('stale', '{}', ${daysAgo(40)})",
                "INSERT INTO resolved_track VALUES ('hitNew', 'a', 't', 'u', 200, NULL, ${daysAgo(40)}), " +
                    "('hitOld', 'b', 't', 'u', 200, NULL, ${daysAgo(200)}), " +
                    "('missNew', NULL, NULL, NULL, 0, NULL, ${daysAgo(5)}), " +
                    "('missOld', NULL, NULL, NULL, 0, NULL, ${daysAgo(40)})",
            ).forEach { statement.execute(it) }
        }
    }

    @After
    fun tearDown() {
        db.close()
        unmockkStatic(Log::class)
    }

    @Test
    fun `one year drops plays older than a year and their lyrics, keeping liked songs and albums`() = runTest {
        history.prune(HistoryRetention.ONE_YEAR, keepLyricsOf = "playing", nowEpochMs = now)

        assertEquals(listOf("likedAlbum", "likedSong", "months", "recent"), db.ids("played_track"))
        assertEquals(listOf("likedAlbum", "likedSong", "months", "playing", "recent"), db.ids("lyrics"))
    }

    @Test
    fun `three months also drops plays from a few months ago and leaves likes and stations`() = runTest {
        val before = snapshot(untouched)

        history.prune(HistoryRetention.THREE_MONTHS, keepLyricsOf = "playing", nowEpochMs = now)

        assertEquals(listOf("likedAlbum", "likedSong", "recent"), db.ids("played_track"))
        assertEquals(listOf("likedAlbum", "likedSong", "playing", "recent"), db.ids("lyrics"))
        assertUnchanged(before)
    }

    @Test
    fun `a play just inside the window stays`() = runTest {
        db.createStatement().use { it.execute(played("edge", daysAgo(92) + 1)) }

        history.prune(HistoryRetention.THREE_MONTHS, keepLyricsOf = null, nowEpochMs = now)

        assertEquals(listOf("edge", "likedAlbum", "likedSong", "recent"), db.ids("played_track"))
    }

    @Test
    fun `forever deletes nothing`() = runTest {
        val before = snapshot(untouched + "played_track" + "lyrics")

        history.prune(HistoryRetention.FOREVER, keepLyricsOf = "playing", nowEpochMs = now)

        assertUnchanged(before)
    }

    @Test
    fun `clear drops all history and every lyric but the playing song's, leaving likes`() = runTest {
        val before = snapshot(listOf("liked_track", "liked_album"))

        history.clear(keepLyricsOf = "playing")

        assertEquals(emptyList<Any>(), db.ids("played_track"))
        assertEquals(listOf("playing"), db.ids("lyrics"))
        assertUnchanged(before)
    }

    @Test
    fun `clear with nothing playing drops every lyric`() = runTest {
        history.clear(keepLyricsOf = null)

        assertEquals(emptyList<Any>(), db.ids("played_track"))
        assertEquals(emptyList<Any>(), db.ids("lyrics"))
    }

    @Test
    fun `removing a song drops its play and its lyrics, leaving likes and other songs`() = runTest {
        val before = snapshot(untouched)

        history.remove("likedSong", keepLyricsOf = "playing")

        assertEquals(listOf("ancient", "likedAlbum", "months", "recent"), db.ids("played_track"))
        assertEquals(listOf("ancient", "likedAlbum", "months", "playing", "recent"), db.ids("lyrics"))
        assertUnchanged(before)
    }

    @Test
    fun `removing the playing song keeps its lyrics`() = runTest {
        history.remove("recent", keepLyricsOf = "recent")

        assertEquals(listOf("ancient", "likedAlbum", "likedSong", "months"), db.ids("played_track"))
        assertEquals(listOf("ancient", "likedAlbum", "likedSong", "months", "recent"), db.ids("lyrics"))
    }

    @Test
    fun `removing one station deletes only that snapshot`() {
        assertEquals(1, db.update(StationSnapshotDao.DELETE_BY_ID, 2L))

        assertEquals(listOf<Any>(1, 3), db.ids("station_snapshot", column = "id"))
    }

    @Test
    fun `forgetting all stations deletes every snapshot`() {
        assertEquals(3, db.update(StationSnapshotDao.DELETE_ALL))

        assertEquals(emptyList<Any>(), db.ids("station_snapshot", column = "id"))
    }

    @Test
    fun `pruning drops expired Last fm answers and lookups even when history is kept forever`() = runTest {
        history.prune(HistoryRetention.FOREVER, keepLyricsOf = null, nowEpochMs = now)

        assertEquals(listOf<Any>("fresh"), db.ids("lastfm_cache", column = "request"))
        assertEquals(listOf<Any>("hitNew", "missNew"), db.ids("resolved_track", column = "nameKey"))
    }

    @Test
    fun `clearing history also clears the Last fm answers and lookups`() = runTest {
        history.clear(keepLyricsOf = null)

        assertEquals(emptyList<Any>(), db.ids("lastfm_cache", column = "request"))
        assertEquals(emptyList<Any>(), db.ids("resolved_track", column = "nameKey"))
    }

    @Test
    fun `removing a song drops the Last fm answers its radio fetched, and only those`() = runTest {
        val creep = "track.getSimilar|artist=radiohead|track=creep|limit=${tech.capullo.tubecapullo.data.similar.LastfmSimilarFinder.SIMILAR_NAMES}"
        db.createStatement().use { it.execute("INSERT INTO lastfm_cache VALUES ('$creep', '{}', ${daysAgo(1)})") }

        history.remove("creep", keepLyricsOf = null)

        assertEquals(listOf<Any>("fresh", "stale"), db.ids("lastfm_cache", column = "request"))
    }
}
