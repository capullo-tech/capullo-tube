package tech.capullo.tubecapullo.data.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.tubecapullo.data.history.LikedEntry
import tech.capullo.tubecapullo.data.youtube.YtVideo

class LikedBackupFormatTest {

    private val a = "dQw4w9WgXcQ"
    private val b = "9bZkp7q19f0"
    private val c = "kJQP7kiw5Fk"

    private fun entry(id: String, likedAt: Long, uploader: String? = "Up $id") = LikedEntry(
        YtVideo(id, "Title $id", uploader, durationSeconds = 212, thumbnailUrl = null),
        likedAt,
    )

    @Test
    fun `export round-trips songs and albums apart, with like dates, newest first`() {
        val entries = listOf(entry(a, 100), entry(b, 300, uploader = null))
        val albums = listOf(entry(c, 50), entry(a, 400))

        val text = LikedBackupFormat.encode(entries, albums, exportedAtEpochMs = 999)
        val parsed = LikedBackupFormat.parse(text)

        parsed as LikedImportParse.Export
        assertEquals(listOf(entry(b, 300, null), entry(a, 100)), parsed.entries)
        assertEquals("an id can be a liked song and a liked album", listOf(entry(a, 400), entry(c, 50)), parsed.albums)
        assertEquals(0, parsed.invalid)
    }

    @Test
    fun `a version 1 file without albums imports every entry as a song`() {
        val text = """{"format":"tubecapullo-liked","version":1,"exportedAt":1,
            "tracks":[{"videoId":"$a","title":"T","durationSeconds":3000,"likedAt":7}]}"""

        val parsed = LikedBackupFormat.parse(text) as LikedImportParse.Export
        assertEquals(listOf(a), parsed.entries.map { it.video.videoId })
        assertTrue(parsed.albums.isEmpty())
    }

    @Test
    fun `export carries the documented schema`() {
        val text = LikedBackupFormat.encode(
            listOf(entry(a, 100, uploader = null)),
            listOf(entry(b, 200)),
            999,
        )
        val root = Json.parseToJsonElement(text).jsonObject

        assertEquals("tubecapullo-liked", root.getValue("format").jsonPrimitive.content)
        assertEquals(
            LikedBackupFormat.VERSION.toString(),
            root.getValue("version").jsonPrimitive.content,
        )
        assertEquals("999", root.getValue("exportedAt").jsonPrimitive.content)
        val track = root.getValue("tracks").jsonArray.single().jsonObject
        assertEquals("https://youtu.be/$a", track.getValue("url").jsonPrimitive.content)
        assertEquals("212", track.getValue("durationSeconds").jsonPrimitive.content)
        assertEquals("100", track.getValue("likedAt").jsonPrimitive.content)
        assertFalse("absent uploader is omitted, not null", "uploader" in track)
        val album = root.getValue("albums").jsonArray.single().jsonObject
        assertEquals("https://youtu.be/$b", album.getValue("url").jsonPrimitive.content)
        assertEquals("200", album.getValue("likedAt").jsonPrimitive.content)
    }

    @Test
    fun `a newer version is rejected even when its schema no longer decodes as v1`() {
        val newer = LikedBackupFormat.VERSION + 1
        val text = """{"format":"tubecapullo-liked","version":$newer,"songs":{"x":1}}"""

        assertEquals(LikedImportParse.NewerVersion(newer), LikedBackupFormat.parse(text))
    }

    @Test
    fun `an older version is accepted`() {
        val text = """{"format":"tubecapullo-liked","version":0,"exportedAt":1,
            "tracks":[{"videoId":"$a","title":"T","durationSeconds":5,"likedAt":7}]}"""

        val parsed = LikedBackupFormat.parse(text) as LikedImportParse.Export
        assertEquals(listOf(a), parsed.entries.map { it.video.videoId })
        assertEquals(7L, parsed.entries.single().likedAtEpochMs)
    }

    @Test
    fun `our format with a broken track is damaged, and a bad id is counted not imported`() {
        val broken = """{"format":"tubecapullo-liked","version":1,"exportedAt":1,
            "tracks":[{"videoId":"$a","likedAt":"yesterday"}]}"""
        assertEquals(LikedImportParse.Damaged, LikedBackupFormat.parse(broken))

        val badId = """{"format":"tubecapullo-liked","version":1,"exportedAt":1,"tracks":[
            {"videoId":"nope","title":"T","durationSeconds":5,"likedAt":7},
            {"videoId":"$b","title":"T","durationSeconds":5,"likedAt":8}]}"""
        val parsed = LikedBackupFormat.parse(badId) as LikedImportParse.Export
        assertEquals(listOf(b), parsed.entries.map { it.video.videoId })
        assertEquals(1, parsed.invalid)
    }

    @Test
    fun `links are found in noisy text, in file order, playlists counted not imported`() {
        val playlist = "PLx0sYbCqOb8TBPRdmBHs5Iftvv9TPboYG"
        val foreign = "OPf0YbXqDm0"
        val text = """
            My favourites (2024):
            1. https://www.youtube.com/watch?v=$a&t=42s - the classic.
            2. [Gangnam](https://youtu.be/$b), and also youtu.be/$a again!
            <a href="https://music.youtube.com/watch?v=$c&list=$playlist">x</a>
            whole album: https://www.youtube.com/playlist?list=$playlist
            same album again https://youtube.com/playlist?list=$playlist.
            not ours: https://vimeo.com/watch?v=$foreign https://notyoutube.com/watch?v=$foreign
            an 11-letter word: information
        """.trimIndent()

        assertEquals(
            LikedImportParse.Links(listOf(a, b, c), playlistsIgnored = 1),
            LikedBackupFormat.parse(text),
        )
    }

    @Test
    fun `text with no youtube link and foreign json are not recognized`() {
        assertEquals(
            LikedImportParse.NotRecognized,
            LikedBackupFormat.parse("shopping list: information, milk, eggs"),
        )
        assertEquals(LikedImportParse.NotRecognized, LikedBackupFormat.parse("""{"a":1}"""))
        assertEquals(LikedImportParse.NotRecognized, LikedBackupFormat.parse("{not json"))
    }

    @Test
    fun `foreign json that carries youtube links imports as links`() {
        val text = """{"songs":["https://youtu.be/$b","https://youtu.be/$a"]}"""

        assertEquals(
            LikedImportParse.Links(listOf(b, a), playlistsIgnored = 0),
            LikedBackupFormat.parse(text),
        )
    }

    @Test
    fun `merge keeps existing likes untouched and collapses duplicates`() {
        val incoming = listOf(entry(a, 100), entry(b, 200), entry(a, 50), entry(c, 300))

        val plan = LikedBackupFormat.merge(incoming, existingIds = setOf(b))

        // b is already liked: not re-inserted, so its own likedAt stays. The first a wins.
        assertEquals(listOf(entry(a, 100), entry(c, 300)), plan.toInsert)
        assertEquals(1, plan.alreadyLiked)
    }

    @Test
    fun `link plan dates by file position so the first link is newest`() {
        val plan = LikedBackupFormat.planLinks(listOf(a, b, a, c), existingIds = setOf(b), 1_000)

        assertEquals(listOf(PendingLike(a, 1_000), PendingLike(c, 998)), plan.toResolve)
        assertEquals(1, plan.alreadyLiked)
    }

    @Test
    fun `summary lists only the non-zero parts`() {
        assertEquals(
            "Imported 38 · 2 already liked · 1 unavailable",
            LikedBackupFormat.summary(38, 0, 2, 1, failed = 0, playlistsIgnored = 0),
        )
        assertEquals("5 already liked", LikedBackupFormat.summary(0, 0, 5, 0, 0, 0))
        assertTrue(LikedBackupFormat.summary(0, 0, 0, 0, 0, 0).isNotBlank())
        assertEquals(
            "Imported 38 songs and 1 album",
            LikedBackupFormat.summary(38, 1, 0, 0, failed = 0, playlistsIgnored = 0),
        )
        assertEquals("Imported 2 albums", LikedBackupFormat.summary(0, 2, 0, 0, 0, 0))
    }
}
