package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import tech.capullo.tubecapullo.data.lyrics.LyricsQuery
import tech.capullo.tubecapullo.station.inTrackWindow
import tech.capullo.tubecapullo.util.TitleCleaner
import java.util.Locale

/**
 * PR 1 of PLAN.md: does Last.fm track.getSimilar add songs YouTube's Mix and related miss?
 * Run: LASTFM_PROBE=1 LASTFM_API_KEY=... ./gradlew :app:testDebugUnitTest --tests '*LastfmSimilarProbeTest*' -i
 * Prints PROBE lines; the pass criteria q1-q6 live in PLAN.md.
 */
class LastfmSimilarProbeTest {
    private lateinit var client: YouTubeExtractorClient
    private lateinit var key: String
    private val http = OkHttpClient()
    private var lastCallMs = 0L

    @Before
    fun setUp() {
        assumeTrue("set LASTFM_PROBE=1 to run the probe", System.getenv("LASTFM_PROBE") == "1")
        key = System.getenv("LASTFM_API_KEY").orEmpty()
        assumeTrue("set LASTFM_API_KEY", key.isNotEmpty())
        client = probeClient()
        runBlocking { client.search("music", limit = 1) }
    }

    private class Row(val regional: Boolean, var parsed: Boolean = false, var similar: Int = 0) {
        var tried = 0
        var found = 0
        var novel = 0
        val foundMs = mutableListOf<Long>()
    }

    @Test
    fun `q1-q6 lastfm similar tracks`() = runBlocking {
        val rows = (SEEDS.map { it to false } + REGIONAL.map { it to true }).mapNotNull { (spec, regional) ->
            runCatching { measure(spec, Row(regional)) }
                .onFailure { println("PROBE seed spec=\"$spec\" threw ${it::class.simpleName}: ${it.message}") }
                .getOrNull()
        }
        report("all", rows)
        report("regional", rows.filter { it.regional })
        report("other", rows.filterNot { it.regional })
    }

    private suspend fun measure(spec: String, row: Row): Row {
        val seed = client.search(spec, limit = 1).firstOrNull() ?: error("no search result")
        val query = LyricsQuery.of(seed.title, seed.uploader)
        val artist = query?.artist
        println("PROBE seed spec=\"$spec\" id=${seed.videoId} title=\"${seed.title}\" uploader=\"${seed.uploader}\" artist=\"$artist\" song=\"${query?.title}\"")
        if (query == null || artist == null) return row
        row.parsed = true
        val similar = similar(artist, query.title)
        row.similar = similar.size
        println("PROBE similar seed=${seed.videoId} n=${similar.size}")
        if (similar.isEmpty()) return row

        val mix = client.mix(seed.videoId)
        val related = client.relatedStreams(seed.videoId).videos
        val known = (mix + related).mapNotNull { TitleCleaner.key(it.title, it.uploader) }.toSet() +
            (mix + related).map { it.videoId }.toSet()

        SAMPLE_RANKS.filter { it <= similar.size }.forEach { rank ->
            val (simArtist, simTitle, match) = similar[rank - 1]
            row.tried++
            val start = System.currentTimeMillis()
            val hit = runCatching { client.search("$simArtist $simTitle", limit = 5) }.getOrDefault(emptyList())
                .firstOrNull { matches(it, simArtist, simTitle) && it.inTrackWindow() }
            val ms = System.currentTimeMillis() - start
            val novel = hit != null && hit.videoId !in known && TitleCleaner.key(hit.title, hit.uploader) !in known
            if (hit != null) {
                row.found++
                row.foundMs += ms
                if (novel) row.novel++
            }
            println(
                "PROBE cand seed=${seed.videoId} rank=$rank match=${"%.3f".format(Locale.ROOT, match)} " +
                    "want=\"$simArtist - $simTitle\" found=${hit?.videoId ?: "-"} got=\"${hit?.title ?: ""}\" " +
                    "novel=$novel ms=$ms",
            )
        }
        return row
    }

    private fun matches(video: YtVideo, artist: String, title: String): Boolean {
        val q = LyricsQuery.of(video.title, video.uploader) ?: return false
        val wantTitle = norm(title)
        val gotTitle = norm(q.title)
        if (wantTitle.isEmpty() || !(gotTitle == wantTitle || gotTitle.startsWith(wantTitle))) return false
        val wantArtist = norm(artist)
        val haystack = norm(q.artist.orEmpty()) + "|" + norm(video.uploader.orEmpty())
        return wantArtist.isNotEmpty() && wantArtist in haystack
    }

    private fun similar(artist: String, title: String): List<Triple<String, String, Double>> {
        val wait = lastCallMs + LASTFM_GAP_MS - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        lastCallMs = System.currentTimeMillis()
        val url = "https://ws.audioscrobbler.com/2.0/".toHttpUrl().newBuilder()
            .addQueryParameter("method", "track.getSimilar")
            .addQueryParameter("artist", artist)
            .addQueryParameter("track", title)
            .addQueryParameter("autocorrect", "1")
            .addQueryParameter("limit", SIMILAR_NAMES.toString())
            .addQueryParameter("api_key", key)
            .addQueryParameter("format", "json")
            .build()
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", UA).build())
            .execute().use { it.body?.string().orEmpty() }
        val root = Json.parseToJsonElement(body).jsonObject
        root["error"]?.let { println("PROBE lastfm error=$it message=${root["message"]}") }
        val tracks = root["similartracks"]?.jsonObject?.get("track") as? JsonArray ?: return emptyList()
        return tracks.map { it.jsonObject }.map { t ->
            Triple(
                (t["artist"] as JsonObject)["name"]!!.jsonPrimitive.content,
                t["name"]!!.jsonPrimitive.content,
                t["match"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
            )
        }
    }

    private fun report(label: String, rows: List<Row>) {
        val parsed = rows.filter { it.parsed }
        val tried = rows.sumOf { it.tried }
        val found = rows.sumOf { it.found }
        val ms = rows.flatMap { it.foundMs }.sorted()
        println(
            "PROBE summary set=$label seeds=${rows.size} " +
                "q1_parsed=${share(parsed.size, rows.size)} " +
                "q2_similar10=${share(parsed.count { it.similar >= 10 }, parsed.size)} " +
                "q3_found=${share(found, tried)} ($found/$tried) " +
                "q4_novel=${share(rows.sumOf { it.novel }, found)} " +
                "q5_medianMs=${ms.getOrNull(ms.size / 2) ?: "-"}",
        )
    }

    private companion object {
        val SEEDS = listOf(
            "Rick Astley Never Gonna Give You Up",
            "Luis Fonsi Despacito",
            "PSY Gangnam Style",
            "Burna Boy Last Last",
            "Aya Nakamura Djadja",
            "Miles Davis So What",
            "Rammstein Du Hast",
            "Arijit Singh Tum Hi Ho",
            "Buena Vista Social Club Chan Chan",
            "Fairuz Kifak Inta",
            "Mdou Moctar Afrique Victime",
            "Ichiko Aoba Asleep Among Endives",
            "Radiohead Creep",
            "Imagine Dragons Thunder",
        )
        val REGIONAL = listOf(
            "Soda Stereo De Musica Ligera",
            "Bad Bunny Titi Me Pregunto",
            "Rosalia Malamente",
            "Los Fabulosos Cadillacs Matador",
            "Natalia Lafourcade Hasta la Raiz",
            "Peso Pluma Ella Baila Sola",
            "Juan Gabriel Querida",
            "Caifanes La Negra Tomasa",
            "Grupo Frontera un x100to",
            "Mon Laferte Tu Falta De Querer",
        )
        val SAMPLE_RANKS = listOf(1, 2, 3, 4, 11, 12, 13, 14)
        const val SIMILAR_NAMES = 40
        const val LASTFM_GAP_MS = 1_000L
        const val UA = "CapulloTube-probe/0.1 (https://github.com/capullo-tech/capullo-tube)"

        fun norm(s: String): String = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        fun share(part: Int, whole: Int): String = if (whole == 0) "-" else "%.2f".format(Locale.ROOT, part.toDouble() / whole)
    }
}
