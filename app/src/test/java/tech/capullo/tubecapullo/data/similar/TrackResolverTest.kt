package tech.capullo.tubecapullo.data.similar

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.tubecapullo.data.history.ResolvedTrackDao
import tech.capullo.tubecapullo.data.history.ResolvedTrackEntity
import tech.capullo.tubecapullo.data.youtube.YouTubeError
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtVideo
import java.util.concurrent.TimeUnit

class TrackResolverTest {
    private val rows = HashMap<String, ResolvedTrackEntity>()
    private val dao = object : ResolvedTrackDao {
        override suspend fun byKey(nameKey: String) = rows[nameKey]
        override suspend fun upsert(entity: ResolvedTrackEntity) {
            rows[entity.nameKey] = entity
        }
    }
    private val extractor = mockk<YouTubeExtractorClient>()
    private var clock = 1_000_000_000_000L
    private val resolver = TrackResolver(extractor, dao).also { it.now = { clock } }

    private val everlong = YtVideo("eBG7P-K-r1Y", "Foo Fighters - Everlong (Official HD Video)", "Foo Fighters", 250, "https://i/1")

    @Test
    fun `a found song is searched once, then served from the cache`() = runTest {
        coEvery { extractor.search(any(), any()) } returns listOf(everlong)

        val first = resolver.resolve("Foo Fighters", "Everlong")
        val second = resolver.resolve("foo fighters", "EVERLONG")

        assertEquals(Resolved(everlong, searched = true), first)
        assertEquals(everlong.videoId, second.video?.videoId)
        assertFalse("the second lookup costs no search", second.searched)
        coVerify(exactly = 1) { extractor.search("Foo Fighters Everlong", TrackResolver.RESOLVE_SEARCH_RESULTS) }
    }

    @Test
    fun `a song with no matching upload is remembered as a miss, then searched again after it expires`() = runTest {
        coEvery { extractor.search(any(), any()) } returns listOf(YtVideo("x", "Someone - Else", "Someone", 200, null))

        assertNull(resolver.resolve("Rubén González", "Fabiando").video)
        val cached = resolver.resolve("Rubén González", "Fabiando")
        assertNull(cached.video)
        assertFalse(cached.searched)

        clock += TimeUnit.DAYS.toMillis(TrackResolver.RESOLVE_MISS_DAYS)
        assertTrue("an expired miss is searched again", resolver.resolve("Rubén González", "Fabiando").searched)
        coVerify(exactly = 2) { extractor.search(any(), any()) }
    }

    @Test
    fun `a hit outlives a miss, and expires after its own window`() = runTest {
        coEvery { extractor.search(any(), any()) } returns listOf(everlong)
        resolver.resolve("Foo Fighters", "Everlong")

        clock += TimeUnit.DAYS.toMillis(TrackResolver.RESOLVE_MISS_DAYS)
        assertFalse(resolver.resolve("Foo Fighters", "Everlong").searched)
        clock += TimeUnit.DAYS.toMillis(TrackResolver.RESOLVE_HIT_DAYS)
        assertTrue(resolver.resolve("Foo Fighters", "Everlong").searched)
    }

    @Test
    fun `a failed search is not cached as a miss`() = runTest {
        coEvery { extractor.search(any(), any()) } throws YouTubeError.BotCheck()
        runCatching { resolver.resolve("Foo Fighters", "Everlong") }
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a joint credit and its first artist share one cache row`() {
        assertEquals(
            TrackResolver.nameKey("Grupo Frontera", "UN X100TO"),
            TrackResolver.nameKey("Grupo Frontera x Bad Bunny", "Un x100to"),
        )
    }
}
