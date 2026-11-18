package tech.capullo.tubecapullo.data.lyrics

import android.os.SystemClock
import android.util.Log
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tech.capullo.tubecapullo.data.history.LyricsDao
import tech.capullo.tubecapullo.data.history.LyricsEntity
import tech.capullo.tubecapullo.data.lyrics.LyricsRepository.Companion.KIND_NONE
import tech.capullo.tubecapullo.data.lyrics.LyricsRepository.Companion.KIND_SYNCED
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.youtube.YouTubeExtractorClient
import tech.capullo.tubecapullo.data.youtube.YtVideo
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LyricsRepositoryTest {

    private class FakeDao : LyricsDao {
        val rows = mutableMapOf<String, LyricsEntity>()

        override suspend fun byId(videoId: String): LyricsEntity? = rows[videoId]

        override suspend fun upsert(entity: LyricsEntity) {
            rows[entity.videoId] = entity
        }

        override suspend fun setOffset(videoId: String, offsetMs: Long) {
            rows[videoId]?.let { rows[videoId] = it.copy(offsetMs = offsetMs) }
        }

        override suspend fun deleteUnplayed(keep: String) {
            rows.keys.retainAll { it == keep }
        }
    }

    private val dao = FakeDao()
    private val online = MutableStateFlow(true)
    private val settings = mockk<AppSettings> { every { lyricsOnline } returns online }
    private val lrclib = mockk<LrclibClient>()
    private val extractor = mockk<YouTubeExtractorClient>()

    private val video = YtVideo("vid", "Artist - Song", "Artist", 200, null)

    private val chorus = listOf("Stay with me tonight", "Hold on to the light")
    private val descriptionLyrics = (
        listOf("Walking down the empty road", "Counting every falling star", "Thinking how it used to be", "Wondering where you are") +
            "" + chorus + "" +
            listOf("Morning comes and you are gone", "Coffee cold upon the stair", "Every song reminds me of", "The way you wore your hair") +
            "" + chorus
        ).joinToString("\n")

    private val syncedRecord = LrclibRecord(duration = 200.0, syncedLyrics = "[00:01.00]a\n[00:02.00]b", plainLyrics = "a\nb")

    @Before
    fun setUp() {
        mockkStatic(Log::class, SystemClock::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { SystemClock.elapsedRealtime() } returns 0L
    }

    @After
    fun tearDown() = unmockkStatic(Log::class, SystemClock::class)

    private fun TestScope.repository() = LyricsRepository(dao, lrclib, extractor, settings, this)

    private fun TestScope.play(repository: LyricsRepository): LyricsState.Done {
        repository.onTrackStarted(video)
        advanceUntilIdle()
        return repository.current.value as LyricsState.Done
    }

    @Test
    fun `lyrics found with the online lookup off are not cached, so turning it on asks LRCLIB`() = runTest {
        val repository = repository()
        online.value = false
        coEvery { extractor.description(any()) } returns descriptionLyrics

        val offline = play(repository)

        assertEquals(LyricsSource.DESCRIPTION, offline.lyrics?.source)
        assertTrue(dao.rows.isEmpty())
        coVerify(exactly = 0) { lrclib.find(any(), any()) }

        coEvery { extractor.description(any()) } returns ""
        assertNull(play(repository).lyrics)
        assertTrue(dao.rows.isEmpty())

        online.value = true
        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_GET, syncedRecord)

        val found = play(repository)

        assertEquals(LyricsSource.LRCLIB_GET, found.lyrics?.source)
        assertTrue(found.lyrics!!.synced)
        assertEquals(KIND_SYNCED, dao.rows.getValue(video.videoId).kind)
        coVerify(exactly = 1) { lrclib.find(any(), 200) }
    }

    @Test
    fun `an instrumental LRCLIB record is cached as no lyrics without reading the description`() = runTest {
        val instrumental = LrclibRecord(duration = 200.0, instrumental = true)
        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_SEARCH, instrumental)

        val done = play(repository())

        assertNull(done.lyrics)
        assertFalse(done.failed)
        assertEquals(KIND_NONE, dao.rows.getValue(video.videoId).kind)
        coVerify(exactly = 0) { extractor.description(any()) }
    }

    @Test
    fun `an LRCLIB outage still reads the description, and a failed lookup is not cached and is retried`() = runTest {
        val repository = repository()
        coEvery { lrclib.find(any(), any()) } throws IOException("offline")
        coEvery { extractor.description(any()) } returns descriptionLyrics

        assertEquals(LyricsSource.DESCRIPTION, play(repository).lyrics?.source)
        assertTrue(dao.rows.isEmpty())

        coEvery { extractor.description(any()) } throws IOException("offline")
        val failed = play(repository)
        assertNull(failed.lyrics)
        assertTrue(failed.failed)
        assertTrue(dao.rows.isEmpty())

        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_GET, syncedRecord)
        repository.retryFailed(video)
        advanceUntilIdle()

        val retried = repository.current.value as LyricsState.Done
        assertEquals(LyricsSource.LRCLIB_GET, retried.lyrics?.source)
        assertFalse(retried.failed)
    }

    @Test
    fun `a video longer than a song is not looked up anywhere`() = runTest {
        val repository = repository()
        repository.onTrackStarted(video.copy(durationSeconds = 3_600))
        advanceUntilIdle()

        assertNull((repository.current.value as LyricsState.Done).lyrics)
        assertTrue(dao.rows.isEmpty())
        coVerify(exactly = 0) { lrclib.find(any(), any()) }
        coVerify(exactly = 0) { extractor.description(any()) }
    }

    @Test
    fun `a partly stamped LRCLIB entry without plain lyrics shows every line, unsynced`() = runTest {
        val partial = LrclibRecord(duration = 200.0, syncedLyrics = "[00:01.00]one\ntwo\n[00:03.00]three")
        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_GET, partial)

        val lyrics = play(repository()).lyrics!!

        assertFalse(lyrics.synced)
        assertEquals(listOf("one", "two", "three"), lyrics.lines.map { it.text })
    }

    @Test
    fun `a per-song nudge is stored with the cached row and comes back on the next play`() = runTest {
        val repository = repository()
        coEvery { lrclib.find(any(), any()) } returns LrclibMatch(LyricsSource.LRCLIB_GET, syncedRecord)
        play(repository)

        repository.nudge(video.videoId, LyricsTiming.SONG_NUDGE_STEP_MS)
        repository.nudge(video.videoId, LyricsTiming.SONG_NUDGE_STEP_MS)
        repository.nudge("other", LyricsTiming.SONG_NUDGE_STEP_MS)
        advanceUntilIdle()

        assertEquals(1_000L, (repository.current.value as LyricsState.Done).offsetMs)
        assertEquals(1_000L, dao.rows.getValue(video.videoId).offsetMs)
        assertEquals(1_000L, play(repository).offsetMs)
        coVerify(exactly = 1) { lrclib.find(any(), any()) }
    }
}
