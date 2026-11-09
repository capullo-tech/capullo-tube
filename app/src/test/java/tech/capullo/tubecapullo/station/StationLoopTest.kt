package tech.capullo.tubecapullo.station

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.audio.contracts.MediaRequest
import tech.capullo.audio.contracts.MediaSourceProvider
import tech.capullo.audio.contracts.PlaybackQueue

/**
 * Verifies the three contract obligations StationLoop mirrors from the engine's internal
 * QueuePlaybackLoop (see StationLoop's KDoc). The fake models failure BY CAUSE (an id marked
 * unresolvable always throws), not by call index, so code that cheats the schedule can't pass.
 */
class StationLoopTest {

    private class FakeSource(
        ids: List<String>,
        private val unresolvable: Set<String> = emptySet(),
    ) : MediaSourceProvider {
        val queueIds = ids.toMutableList()
        val calls = mutableListOf<String>()

        override fun queue(): PlaybackQueue = object : PlaybackQueue {
            override val size: Int get() = queueIds.size
            override val currentIndex: Int = 0
            override val isRotating: Boolean = false
            override fun idAt(index: Int): String? = queueIds.getOrNull(index)
        }

        override suspend fun mediaRequestFor(id: String): MediaRequest {
            calls += "resolve:$id"
            if (id in unresolvable) throw IllegalStateException("unresolvable $id")
            return MediaRequest("https://example/$id", "audio/mp4")
        }

        override fun onQueueAdvanced(index: Int) {
            calls += "advanced:$index"
        }
    }

    private class RecordingPlayback : StationLoop.Playback {
        val played = mutableListOf<String>()
        override fun play(request: MediaRequest) {
            played += request.uri
        }
    }

    @Test
    fun `obligation 1 - resolve comes before onQueueAdvanced, play in between`() = runTest {
        val source = FakeSource(listOf("aaaaaaaaaaa"))
        val playback = RecordingPlayback()
        StationLoop(source, playback).start()

        assertEquals(listOf("resolve:aaaaaaaaaaa", "advanced:0"), source.calls)
        assertEquals(listOf("https://example/aaaaaaaaaaa"), playback.played)
    }

    @Test
    fun `obligation 2 - a throw skips the track and plays the next`() = runTest {
        val source = FakeSource(listOf("bad00000000", "good0000000"), unresolvable = setOf("bad00000000"))
        val playback = RecordingPlayback()
        val loop = StationLoop(source, playback)
        loop.start()

        assertEquals(listOf("https://example/good0000000"), playback.played)
        assertEquals(1, loop.currentIndex)
        // The skipped track must NOT have been reported as advanced-to.
        assertEquals(listOf("resolve:bad00000000", "resolve:good0000000", "advanced:1"), source.calls)
    }

    @Test
    fun `obligation 2 - an all-unresolvable queue terminates instead of spinning`() = runTest {
        val ids = listOf("bad00000001", "bad00000002", "bad00000003")
        val source = FakeSource(ids, unresolvable = ids.toSet())
        val playback = RecordingPlayback()
        var exhausted = false
        val loop = StationLoop(source, playback)
        loop.onQueueExhausted = { exhausted = true }
        loop.start()

        assertTrue(playback.played.isEmpty())
        assertTrue("exhausted callback must fire", exhausted)
        // Bounded: each track tried at most once in this pass.
        assertEquals(3, source.calls.count { it.startsWith("resolve:") })
    }

    @Test
    fun `advance past the end of a finite queue reports exhaustion`() = runTest {
        val source = FakeSource(listOf("only0000000"))
        val playback = RecordingPlayback()
        var exhausted = false
        val loop = StationLoop(source, playback)
        loop.onQueueExhausted = { exhausted = true }
        loop.start()
        loop.advance()

        assertEquals(1, playback.played.size)
        assertTrue(exhausted)
    }

    @Test
    fun `onTrackChanged fires with the index that actually played`() = runTest {
        val source = FakeSource(listOf("bad00000000", "good0000000"), unresolvable = setOf("bad00000000"))
        val changes = mutableListOf<Int>()
        val loop = StationLoop(source, RecordingPlayback())
        loop.onTrackChanged = { changes += it }
        loop.start()

        assertEquals(listOf(1), changes)
    }
}
