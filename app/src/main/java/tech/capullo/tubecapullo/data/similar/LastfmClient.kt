package tech.capullo.tubecapullo.data.similar

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import tech.capullo.tubecapullo.data.history.LastfmCacheDao
import tech.capullo.tubecapullo.data.history.LastfmCacheEntity
import tech.capullo.tubecapullo.data.youtube.YtVideo
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class LastfmException(val code: Int, message: String) : Exception("Last.fm error $code: $message")

/**
 * Read-only Last.fm calls. Each takes the API key so a personal key can replace the built-in
 * one. Answers are cached for [CACHE_DAYS] (the terms ask for caching) and every network call
 * passes the [gate].
 */
@Singleton
class LastfmClient @Inject constructor(okHttp: OkHttpClient, private val cache: LastfmCacheDao) {
    internal var now: () -> Long = System::currentTimeMillis

    val gate = LastfmGate { now() }

    // Built on the first call: the client is injected at app start, even with the feature off.
    private val http by lazy {
        HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            expectSuccess = false
            install(UserAgent) { agent = USER_AGENT }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
    }

    suspend fun similarTracks(artist: String, title: String, limit: Int, apiKey: String): List<SimilarTrack> = parseSimilarTracks(
        call(apiKey, "track.getSimilar", "artist" to artist, "track" to title, "limit" to "$limit"),
    )

    suspend fun similarArtists(artist: String, limit: Int, apiKey: String): List<SimilarArtist> = parseSimilarArtists(call(apiKey, "artist.getSimilar", "artist" to artist, "limit" to "$limit"))

    suspend fun topTracks(artist: String, limit: Int, apiKey: String): List<SimilarTrack> = parseTopTracks(call(apiKey, "artist.getTopTracks", "artist" to artist, "limit" to "$limit"))

    private suspend fun call(apiKey: String, method: String, vararg params: Pair<String, String>): String {
        val request = requestKey(method, params.toList())
        cache.byRequest(request)
            ?.takeIf { now() - it.fetchedAtEpochMs < TimeUnit.DAYS.toMillis(CACHE_DAYS) }
            ?.let { return it.body }
        gate.acquire(apiKey)
        val (status, body) = transport(apiKey, method, params.toList())
        val parsed = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        val code = (parsed?.get("error") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: if (status == HTTP_TOO_MANY_REQUESTS) LastfmGate.ERROR_RATE_LIMITED else null
        gate.onAnswer(apiKey, code)
        // Only a real answer is kept: an HTML error page or a captive portal is not.
        if (status in 200..299 && parsed != null && (code == null || code == ERROR_NOT_FOUND)) {
            cache.upsert(LastfmCacheEntity(request, body, now()))
        }
        if (parsed == null) throw LastfmException(code ?: status, "not a Last.fm answer (HTTP $status)")
        return body
    }

    internal var transport: suspend (String, String, List<Pair<String, String>>) -> Pair<Int, String> = { apiKey, method, params ->
        val response = http.get(BASE_URL) {
            parameter("method", method)
            params.forEach { (name, value) -> parameter(name, value) }
            parameter("autocorrect", "1")
            parameter("api_key", apiKey)
            parameter("format", "json")
        }
        response.status.value to response.bodyAsText()
    }

    internal companion object {
        private const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"

        private const val USER_AGENT = "CapulloTube (https://github.com/capullo-tech/capullo-tube)"

        private const val CONNECT_TIMEOUT_MS = 10_000L

        private const val REQUEST_TIMEOUT_MS = 10_000L

        /** "Track not found" / "Artist not found": an empty answer, not a failure. */
        const val ERROR_NOT_FOUND = 6

        fun requestKey(method: String, params: List<Pair<String, String>>): String = method + params.joinToString("") { (name, value) -> "|$name=${SeedNames.key(value)}" }

        /** The cache rows a radio from [video] can have written: one per name try. */
        fun similarRequestKeys(video: YtVideo): List<String> = SeedNames.queries(video).map { query ->
            requestKey(
                "track.getSimilar",
                listOf("artist" to query.artist, "track" to query.title, "limit" to "${LastfmSimilarFinder.SIMILAR_NAMES}"),
            )
        }

        /** GUESS: similarity lists change slowly. */
        const val CACHE_DAYS = 30L

        private const val HTTP_TOO_MANY_REQUESTS = 429

        fun parseSimilarTracks(body: String): List<SimilarTrack> = items(body, "similartracks", "track").mapNotNull { track ->
            val name = track.text("name") ?: return@mapNotNull null
            val artist = track.obj("artist")?.text("name") ?: return@mapNotNull null
            SimilarTrack(artist, name, track.number("match") ?: 0.0)
        }

        fun parseSimilarArtists(body: String): List<SimilarArtist> = items(body, "similarartists", "artist").mapNotNull { artist ->
            val name = artist.text("name") ?: return@mapNotNull null
            SimilarArtist(name, artist.number("match") ?: 0.0)
        }

        fun parseTopTracks(body: String): List<SimilarTrack> = items(body, "toptracks", "track").mapNotNull { track ->
            val name = track.text("name") ?: return@mapNotNull null
            val artist = track.obj("artist")?.text("name") ?: return@mapNotNull null
            SimilarTrack(artist, name, 0.0)
        }

        private fun items(body: String, container: String, item: String): List<JsonObject> {
            val root = Json.parseToJsonElement(body).jsonObject
            (root["error"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.let { code ->
                if (code == ERROR_NOT_FOUND) return emptyList()
                throw LastfmException(code, root.text("message") ?: "")
            }
            // Last.fm sends a lone object instead of a one-item array.
            return when (val list = (root[container] as? JsonObject)?.get(item)) {
                is JsonArray -> list.mapNotNull { it as? JsonObject }
                is JsonObject -> listOf(list)
                else -> emptyList()
            }
        }

        private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

        private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun JsonObject.number(key: String): Double? = (get(key) as? JsonElement)?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
    }
}
