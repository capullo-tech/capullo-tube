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

    private val http = HttpClient(OkHttp) {
        engine { preconfigured = okHttp }
        expectSuccess = false
        install(UserAgent) { agent = USER_AGENT }
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    suspend fun similarTracks(artist: String, title: String, limit: Int, apiKey: String): List<SimilarTrack> = parseSimilarTracks(
        call(apiKey, "track.getSimilar", "artist" to artist, "track" to title, "limit" to "$limit"),
    )

    suspend fun similarArtists(artist: String, limit: Int, apiKey: String): List<SimilarArtist> = parseSimilarArtists(call(apiKey, "artist.getSimilar", "artist" to artist, "limit" to "$limit"))

    suspend fun topTracks(artist: String, limit: Int, apiKey: String): List<SimilarTrack> = parseTopTracks(call(apiKey, "artist.getTopTracks", "artist" to artist, "limit" to "$limit"))

    private suspend fun call(apiKey: String, method: String, vararg params: Pair<String, String>): String {
        val request = method + params.joinToString("") { (name, value) -> "|$name=${SeedNames.key(value)}" }
        cache.byRequest(request)
            ?.takeIf { now() - it.fetchedAtEpochMs < TimeUnit.DAYS.toMillis(CACHE_DAYS) }
            ?.let { return it.body }
        gate.acquire(apiKey)
        val body = transport(apiKey, method, params.toList())
        val code = errorCode(body)
        gate.onAnswer(apiKey, code)
        if (code == null || code == ERROR_NOT_FOUND) cache.upsert(LastfmCacheEntity(request, body, now()))
        return body
    }

    internal var transport: suspend (String, String, List<Pair<String, String>>) -> String = { apiKey, method, params ->
        http.get(BASE_URL) {
            parameter("method", method)
            params.forEach { (name, value) -> parameter(name, value) }
            parameter("autocorrect", "1")
            parameter("api_key", apiKey)
            parameter("format", "json")
        }.bodyAsText()
    }

    internal companion object {
        private const val BASE_URL = "https://ws.audioscrobbler.com/2.0/"

        private const val USER_AGENT = "CapulloTube (https://github.com/capullo-tech/capullo-tube)"

        private const val CONNECT_TIMEOUT_MS = 10_000L

        private const val REQUEST_TIMEOUT_MS = 10_000L

        /** "Track not found" / "Artist not found": an empty answer, not a failure. */
        const val ERROR_NOT_FOUND = 6

        /** GUESS: similarity lists change slowly. */
        const val CACHE_DAYS = 30L

        fun errorCode(body: String): Int? = runCatching {
            (Json.parseToJsonElement(body).jsonObject["error"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        }.getOrNull()

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
