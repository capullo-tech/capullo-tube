package tech.capullo.tubecapullo.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** One LRCLIB entry, as `/api/get` and `/api/search` return it. [duration] is seconds. */
@Serializable
data class LrclibRecord(
    val id: Long = 0,
    val trackName: String? = null,
    val artistName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/** What LRCLIB found for a song, and which call found it. */
data class LrclibMatch(val source: LyricsSource, val record: LrclibRecord)

/**
 * LRCLIB (lrclib.net), a free lyrics database with no key. Two calls, in order:
 * `/api/get` with the artist, the song and the duration, then `/api/search` with "artist song",
 * keeping the result nearest in duration. A result counts only within
 * [LYRICS_DURATION_TOLERANCE_S] of the video ([bestMatch]). LRCLIB receives the artist, the song
 * and the duration; nothing else about the listener.
 */
@Singleton
class LrclibClient @Inject constructor() {

    private val http = HttpClient(OkHttp) {
        expectSuccess = false
        // LRCLIB asks every client to name itself and a homepage in its User-Agent.
        install(UserAgent) { agent = USER_AGENT }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The lyrics for [query] in a [durationS]-second video, or null when LRCLIB has none
     *  within the tolerance. Throws on a network or server failure, so a failure is never
     *  cached as "no lyrics". */
    suspend fun find(query: LyricsQuery, durationS: Long): LrclibMatch? {
        query.artist?.let { artist ->
            get(artist, query.title, durationS)
                ?.let { bestMatch(listOf(it), durationS) }
                ?.let { return LrclibMatch(LyricsSource.LRCLIB_GET, it) }
        }
        return bestMatch(search(query.searchText), durationS)
            ?.let { LrclibMatch(LyricsSource.LRCLIB_SEARCH, it) }
    }

    private suspend fun get(artist: String, title: String, durationS: Long): LrclibRecord? {
        val response = http.get("$BASE_URL/api/get") {
            parameter("artist_name", artist)
            parameter("track_name", title)
            parameter("duration", durationS)
        }
        if (response.status == HttpStatusCode.NotFound) return null
        check(response.status.isSuccess()) { "LRCLIB get answered ${response.status.value}" }
        return json.decodeFromString<LrclibRecord>(response.bodyAsText())
    }

    private suspend fun search(text: String): List<LrclibRecord> {
        val response = http.get("$BASE_URL/api/search") { parameter("q", text) }
        check(response.status.isSuccess()) { "LRCLIB search answered ${response.status.value}" }
        return json.decodeFromString<List<LrclibRecord>>(response.bodyAsText())
    }

    internal companion object {
        /**
         * The widest gap between a video's duration and an LRCLIB entry's that still counts as
         * the same recording, seconds. GUESS 5, from the first probe (2026-11-12,
         * docs/probes/lyrics-results.md): its 90 right matches were 0 to 3.7 s off (89 within
         * 2 s), and its only match 5 to 30 s off (13.1 s) was the wrong song. Lower drops right
         * matches whose video adds a second or two; higher accepts other versions (radio edit,
         * live) and, for synced lyrics, timing that drifts by that much.
         */
        const val LYRICS_DURATION_TOLERANCE_S = 5.0

        /** GUESS: never measured on a phone. The second probe's lookup times are in
         *  docs/probes/lyrics-results.md. Lower fails lookups on a slow network (the lookup is
         *  not cached then, so it retries at the next play); higher only delays a "no lyrics"
         *  that the listener never waits for (playback does not wait on lyrics). */
        private const val CONNECT_TIMEOUT_MS = 10_000L

        /** The whole request, connect to last byte. GUESS, as [CONNECT_TIMEOUT_MS]. */
        private const val REQUEST_TIMEOUT_MS = 15_000L

        private const val BASE_URL = "https://lrclib.net"

        private const val USER_AGENT = "CapulloTube (https://github.com/capullo-tech/capullo-tube)"

        /** The record nearest in duration to a [durationS]-second video, within
         *  [LYRICS_DURATION_TOLERANCE_S], with lyrics; synced first on a tie. Instrumental
         *  entries and entries without a duration never match. */
        fun bestMatch(records: List<LrclibRecord>, durationS: Long): LrclibRecord? = records
            .filter { it.hasLyrics() }
            .mapNotNull { record -> record.duration?.let { record to abs(it - durationS) } }
            .filter { (_, gap) -> gap <= LYRICS_DURATION_TOLERANCE_S }
            .minWithOrNull(
                compareBy<Pair<LrclibRecord, Double>> { (_, gap) -> gap }
                    .thenBy { (record, _) -> record.syncedLyrics.isNullOrBlank() },
            )
            ?.first

        private fun LrclibRecord.hasLyrics(): Boolean = !instrumental &&
            (!syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank())
    }
}
