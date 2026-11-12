package tech.capullo.tubecapullo.data.lyrics

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import tech.capullo.tubecapullo.data.youtube.YouTubeError
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.data.youtube.probeClient
import tech.capullo.tubecapullo.station.StationEngine
import java.util.Locale
import kotlin.math.abs

/**
 * The lyrics probe: LRCLIB coverage with the app's own parser ([LyricsQuery]) and lookup
 * ([LrclibClient]) on the owner's songs, and the precision of the description detector
 * ([DescriptionLyrics]) on their descriptions (population A) and on held-out related videos
 * (population B, criteria section 3b). Network-gated behind `YT_SMOKE=1`, like the other probes:
 *
 * ```
 * YT_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*LyricsProbeTest*'
 * ```
 *
 * **The criteria were written first**, in `docs/probes/lyrics-criteria.md` (sections 2 and 3).
 * The song list is fixed (`src/test/resources/lyrics/owner-songs.json`: the 123 rows of the
 * first run, with that run's query and result), so the runs compare row by row. Only Q1 (every
 * LRCLIB lookup answered) asserts; the rest are `PROBE` lines, recorded in
 * `docs/probes/lyrics-results.md`.
 */
class LyricsProbeTest {

    private lateinit var lrclib: LrclibClient
    private lateinit var youtube: YouTubeExtractorClient

    @Before
    fun setUp() {
        assumeTrue("set YT_SMOKE=1 to run the probe", System.getenv("YT_SMOKE") == "1")
        lrclib = LrclibClient()
        youtube = probeClient()
    }

    @Serializable
    private data class FirstRun(
        val status: String,
        val artist: String,
        val title: String,
        val how: String,
        val durationGapS: Double? = null,
        val synced: Boolean,
        val match: String,
    )

    @Serializable
    private data class Song(
        val kind: String,
        val id: String,
        val title: String,
        val uploader: String? = null,
        val durationS: Long,
        val firstRun: FirstRun,
        val parserTarget: Boolean,
    )

    /** One video's lookup: [match] null for a miss; [error] set when LRCLIB did not answer. */
    private class Lookup(val query: LyricsQuery?, val match: LrclibMatch?, val ms: Long, val error: String?)

    @Test
    fun `q1-q3b LRCLIB coverage and description precision`() = runBlocking {
        val songs = Json.decodeFromString<List<Song>>(
            javaClass.getResource("/lyrics/owner-songs.json")!!.readText(),
        )
        val videos = songs.distinctBy { it.id }
        val lookups = videos.associate { song -> song.id to lookUp(song) }
        val errors = lookups.filterValues { it.error != null }

        songs.forEach { song -> printRow(song, lookups.getValue(song.id)) }
        summarise(songs, lookups)
        recheckLostHits(songs, lookups)
        descriptions(videos, lookups)
        heldOut(songs)

        // After the prints, so one failed lookup does not hide the other numbers.
        assertTrue("Q1 FAIL: ${errors.mapValues { it.value.error }}", errors.isEmpty())
    }

    private suspend fun lookUp(song: Song): Lookup {
        val query = LyricsQuery.of(song.title, song.uploader)
        val started = System.nanoTime()
        val result = runCatching { query?.let { lrclib.find(it, song.durationS) } }
        val ms = (System.nanoTime() - started) / 1_000_000
        delay(PAUSE_MS)
        return Lookup(query, result.getOrNull(), ms, result.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" })
    }

    private fun Lookup.hit(): Boolean = match != null

    private fun printRow(song: Song, lookup: Lookup) {
        val record = lookup.match?.record
        val gap = record?.duration?.let { abs(it - song.durationS) }
        val change = when {
            song.firstRun.status == "hit" && lookup.hit() -> "kept"
            song.firstRun.status == "hit" -> "LOST"
            lookup.hit() -> "NEW"
            else -> "miss"
        }
        println(
            "PROBE row kind=${song.kind} id=${song.id} change=$change target=${song.parserTarget} " +
                "source=${lookup.match?.source?.logName ?: "none"} synced=${!record?.syncedLyrics.isNullOrBlank()} " +
                "gap=${gap?.let { "%.1f".format(Locale.ROOT, it) }} ms=${lookup.ms} " +
                "q=\"${lookup.query?.artist} | ${lookup.query?.title}\" " +
                "match=\"${record?.artistName} - ${record?.trackName}\" title=\"${song.title}\"" +
                (lookup.error?.let { " error=\"$it\"" } ?: ""),
        )
    }

    private fun summarise(songs: List<Song>, lookups: Map<String, Lookup>) {
        fun count(rows: List<Song>) = rows.count { lookups.getValue(it.id).hit() }
        listOf("liked", "played").forEach { kind ->
            val rows = songs.filter { it.kind == kind }
            println("PROBE q2 kind=$kind rows=${rows.size} hits=${count(rows)} firstRun=${rows.count { it.firstRun.status == "hit" }}")
        }
        val hits = songs.filter { lookups.getValue(it.id).hit() }
        val targets = songs.filter { it.parserTarget }
        val lost = songs.filter { it.firstRun.status == "hit" && !lookups.getValue(it.id).hit() }
        val new = songs.filter { it.firstRun.status != "hit" && lookups.getValue(it.id).hit() }
        val synced = hits.count { !lookups.getValue(it.id).match!!.record.syncedLyrics.isNullOrBlank() }
        val bySource = hits.groupingBy { lookups.getValue(it.id).match!!.source.logName }.eachCount()
        println(
            "PROBE q2 all rows=${songs.size} hits=${hits.size} share=${"%.2f".format(Locale.ROOT, hits.size.toDouble() / songs.size)} " +
                "synced=$synced sources=$bySource targetHits=${count(targets)}/${targets.size} " +
                "new=${new.size} lost=${lost.size}",
        )
        val times = lookups.values.map { it.ms }.sorted()
        println("PROBE q2 lookupMs median=${times[times.size / 2]} max=${times.last()} n=${times.size}")
    }

    /** A first-run hit that is now a miss: the first run's own query again, to tell a parser
     *  loss (the old query still hits) from an LRCLIB change (it does not). */
    private suspend fun recheckLostHits(songs: List<Song>, lookups: Map<String, Lookup>) {
        songs.filter { it.firstRun.status == "hit" && !lookups.getValue(it.id).hit() }
            .distinctBy { it.id }
            .forEach { song ->
                val old = LyricsQuery(song.firstRun.artist, song.firstRun.title)
                val again = runCatching { lrclib.find(old, song.durationS) }
                delay(PAUSE_MS)
                println(
                    "PROBE lost id=${song.id} firstRunQuery=\"${old.artist} | ${old.title}\" " +
                        "stillHits=${again.getOrNull() != null} error=${again.exceptionOrNull()?.message}",
                )
            }
    }

    /** Q3, population A (criteria 3 and 3b): the detector on the description of every video
     *  of the owner's songs. */
    private suspend fun descriptions(videos: List<Song>, lookups: Map<String, Lookup>) {
        var fetched = 0
        var accepted = 0
        var filled = 0
        videos.forEach { song ->
            val description = fetchDescription(song.id) ?: return@forEach
            fetched++
            val block = DescriptionLyrics.extract(description) ?: return@forEach
            accepted++
            val lookup = lookups.getValue(song.id)
            if (!lookup.hit()) filled++
            printBlock("A", song.id, song.title, block, lookup.match?.record)
        }
        println(
            "PROBE q3 A videos=${videos.size} fetched=$fetched accepted=$accepted " +
                "acceptedOnLrclibMiss=$filled lrclibMisses=${videos.count { !lookups.getValue(it.id).hit() }}",
        )
    }

    /** Q3b, population B (criteria 3b): the related videos of the liked songs, songs only, not
     *  in the owner's list. Each accepted block is checked against LRCLIB's lyrics for it. */
    private suspend fun heldOut(songs: List<Song>) {
        val own = songs.mapTo(HashSet()) { it.id }
        val videos = LinkedHashMap<String, YtVideo>()
        songs.filter { it.kind == "liked" }.distinctBy { it.id }.forEach { seed ->
            val related = try {
                youtube.relatedStreams(seed.id).videos
            } catch (e: YouTubeError) {
                println("PROBE q3b seed=${seed.id} error=\"${e.message}\"")
                emptyList()
            }
            delay(PAUSE_MS)
            related
                .filter { it.durationSeconds in StationEngine.MIN_TRACK_SECONDS..StationEngine.MAX_TRACK_SECONDS }
                .filter { it.videoId !in own }
                .forEach { videos.putIfAbsent(it.videoId, it) }
        }
        var fetched = 0
        var accepted = 0
        var withLrclib = 0
        var shareRight = 0
        videos.values.forEach { video ->
            val description = fetchDescription(video.videoId) ?: return@forEach
            fetched++
            val block = DescriptionLyrics.extract(description) ?: return@forEach
            accepted++
            val record = LyricsQuery.of(video.title, video.uploader)
                ?.let { query -> runCatching { lrclib.find(query, video.durationSeconds) }.getOrNull() }
                ?.record
            delay(PAUSE_MS)
            if (record != null) withLrclib++
            val share = printBlock("B", video.videoId, video.title, block, record)
            if (share != null && share >= RIGHT_SHARE) shareRight++
        }
        println(
            "PROBE q3b B videos=${videos.size} fetched=$fetched accepted=$accepted " +
                "withLrclib=$withLrclib shareRight=$shareRight toRead=${accepted - shareRight}",
        )
    }

    private suspend fun fetchDescription(id: String): String? = try {
        youtube.description(id)
    } catch (e: YouTubeError) {
        println("PROBE desc id=$id error=\"${e.message}\"")
        null
    } finally {
        delay(PAUSE_MS)
    }

    /** One accepted block: the share of its lines in LRCLIB's lyrics ([record]; null without
     *  them), and the block itself, in full when it must be read by hand (no share, or a share
     *  under [RIGHT_SHARE]). Returns the share. */
    private fun printBlock(population: String, id: String, title: String, block: String, record: LrclibRecord?): Double? {
        val lines = block.lines().filter { it.isNotBlank() }
        val share = record?.let {
            val known = (it.plainLyrics ?: Lrc.parse(it.syncedLyrics.orEmpty()).joinToString("\n") { line -> line.text })
                .lines().mapTo(HashSet(), ::lineKey)
            lines.count { line -> lineKey(line) in known }.toDouble() / lines.size
        }
        println(
            "PROBE desc pop=$population id=$id accepted lines=${lines.size} lrclib=${record != null} " +
                "share=${share?.let { "%.2f".format(Locale.ROOT, it) }} title=\"$title\"",
        )
        val shown = if (share != null && share >= RIGHT_SHARE) block.lines().take(4) else block.lines()
        shown.forEach { println("PROBE descline id=$id | $it") }
        return share
    }

    private fun lineKey(line: String): String = line.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private companion object {
        /** Between calls, as in the first run (0.2 s): a courtesy to LRCLIB and YouTube. */
        const val PAUSE_MS = 200L

        /** A block whose lines are this share of LRCLIB's lyrics is right (criteria section 3). */
        const val RIGHT_SHARE = 0.5
    }
}
