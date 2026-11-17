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
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

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

data class LrclibMatch(val source: LyricsSource, val record: LrclibRecord)

@Singleton
class LrclibClient @Inject constructor(okHttp: OkHttpClient) {

    private val http = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        expectSuccess = false

        install(UserAgent) { agent = USER_AGENT }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

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

        const val LYRICS_DURATION_TOLERANCE_S = 5.0

        const val SYNCED_PREFERENCE_WINDOW_S = 2.0

        private const val CONNECT_TIMEOUT_MS = 10_000L

        private const val REQUEST_TIMEOUT_MS = 15_000L

        private const val BASE_URL = "https://lrclib.net"

        private const val USER_AGENT = "CapulloTube (https://github.com/capullo-tech/capullo-tube)"

        fun bestMatch(records: List<LrclibRecord>, durationS: Long): LrclibRecord? {
            val candidates = records
                .filter { it.instrumental || it.hasLyrics() }
                .mapNotNull { record -> record.duration?.let { record to abs(it - durationS) } }
                .filter { (_, gap) -> gap <= LYRICS_DURATION_TOLERANCE_S }
            val closest = candidates.minOfOrNull { (_, gap) -> gap } ?: return null
            return candidates
                .filter { (_, gap) -> gap <= closest + SYNCED_PREFERENCE_WINDOW_S }
                .minWithOrNull(
                    compareBy<Pair<LrclibRecord, Double>> { (record, _) -> record.rank() }
                        .thenBy { (_, gap) -> gap },
                )
                ?.first
        }

        private fun LrclibRecord.rank(): Int = when {
            instrumental -> 2
            !syncedLyrics.isNullOrBlank() -> 0
            else -> 1
        }

        private fun LrclibRecord.hasLyrics(): Boolean = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank()
    }
}
