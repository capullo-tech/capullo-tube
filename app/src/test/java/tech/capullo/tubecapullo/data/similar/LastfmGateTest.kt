package tech.capullo.tubecapullo.data.similar

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.tubecapullo.data.history.LastfmCacheDao
import tech.capullo.tubecapullo.data.history.LastfmCacheEntity
import tech.capullo.tubecapullo.ui.settings.lastfmProblem
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class LastfmGateTest {
    private val rows = HashMap<String, LastfmCacheEntity>()
    private val cache = object : LastfmCacheDao {
        override suspend fun byRequest(request: String) = rows[request]
        override suspend fun upsert(entity: LastfmCacheEntity) {
            rows[entity.request] = entity
        }
    }
    private val sent = mutableListOf<Pair<Long, String>>()
    private var answer = OK

    private fun TestScope.client() = LastfmClient(OkHttpClient(), cache).also { client ->
        client.now = { currentTime }
        client.transport = { key, _, params ->
            sent += currentTime to (params.first().second + "@" + key)
            answer
        }
    }

    @Test
    fun `calls are spaced at least MIN_GAP_MS apart, even when asked together`() = runTest {
        val client = client()
        (1..5).map { n -> async { client.similarArtists("Artist $n", 10, KEY) } }.awaitAll()

        val times = sent.map { it.first }
        assertEquals(5, times.size)
        times.zipWithNext().forEach { (a, b) -> assertTrue("$times", b - a >= LastfmGate.MIN_GAP_MS) }
    }

    @Test
    fun `a repeated question is answered from the cache, with no call and no wait`() = runTest {
        val client = client()
        client.similarTracks("Radiohead", "Creep", 40, KEY)
        val before = currentTime
        assertEquals(1, client.similarTracks("radiohead", "CREEP", 40, OTHER_KEY).size)

        assertEquals(1, sent.size)
        assertEquals(before, currentTime)
    }

    @Test
    fun `a not-found answer is cached, an error answer is not`() = runTest {
        val client = client()
        answer = """{"error":6,"message":"Track not found"}"""
        client.similarTracks("PSY", "GANGNAM STYLE M/V", 40, KEY)
        client.similarTracks("PSY", "GANGNAM STYLE M/V", 40, KEY)
        assertEquals(1, sent.size)

        answer = """{"error":11,"message":"Service Offline"}"""
        repeat(2) { runCatching { client.similarTracks("Muse", "Hysteria", 40, KEY) } }
        assertEquals(3, sent.size)
    }

    @Test
    fun `a cached answer expires after CACHE_DAYS`() = runTest {
        val client = client()
        var clock = 0L
        client.now = { clock }
        client.similarTracks("Radiohead", "Creep", 40, KEY)
        clock += TimeUnit.DAYS.toMillis(LastfmClient.CACHE_DAYS)
        client.similarTracks("Radiohead", "Creep", 40, KEY)
        assertEquals(2, sent.size)
    }

    @Test
    fun `a rate-limit answer pauses every key for RATE_LIMIT_PAUSE_MS, then calls resume`() = runTest {
        val client = client()
        answer = """{"error":29,"message":"Rate Limit Exceeded"}"""
        assertTrue(runCatching { client.similarArtists("A", 10, KEY) }.exceptionOrNull() is LastfmException)

        answer = OK
        assertTrue(runCatching { client.similarArtists("B", 10, OTHER_KEY) }.exceptionOrNull() is LastfmPaused)
        assertEquals(1, sent.size)

        testScheduler.advanceTimeBy(LastfmGate.RATE_LIMIT_PAUSE_MS + 1)
        client.similarArtists("B", 10, OTHER_KEY)
        assertEquals(2, sent.size)
    }

    @Test
    fun `a suspended or invalid key stops only that key`() = runTest {
        val client = client()
        answer = """{"error":26,"message":"Suspended API key"}"""
        runCatching { client.similarArtists("A", 10, KEY) }

        answer = OK
        assertTrue(runCatching { client.similarArtists("B", 10, KEY) }.exceptionOrNull() is LastfmPaused)
        client.similarArtists("B", 10, OTHER_KEY)
        assertEquals(listOf("A@$KEY", "B@$OTHER_KEY"), sent.map { it.second })
    }

    @Test
    fun `Settings explains a rejected key or a pause, for the key in use only`() {
        val suspended = LastfmStatus(rejectedKeys = mapOf(KEY to LastfmGate.ERROR_SUSPENDED_KEY))
        assertTrue(lastfmProblem(suspended, KEY, 0)!!.contains("suspended"))
        assertNull(lastfmProblem(suspended, OTHER_KEY, 0))
        val invalid = LastfmStatus(rejectedKeys = mapOf(KEY to LastfmGate.ERROR_INVALID_KEY))
        assertTrue(lastfmProblem(invalid, KEY, 0)!!.contains("does not accept"))
        val paused = LastfmStatus(pausedUntilMs = 9 * 60_000L + 1)
        assertEquals("Last.fm asked to slow down: similar songs pause for 10 min", lastfmProblem(paused, KEY, 0))
        assertNull(lastfmProblem(paused, KEY, paused.pausedUntilMs))
    }

    private companion object {
        const val KEY = "0123456789abcdef0123456789abcdef"
        const val OTHER_KEY = "fedcba9876543210fedcba9876543210"
        const val OK = """{"similartracks":{"track":[{"name":"No Surprises","match":1,"artist":{"name":"Radiohead"}}]},""" +
            """"similarartists":{"artist":[{"name":"Muse","match":"1"}]}}"""
    }
}
