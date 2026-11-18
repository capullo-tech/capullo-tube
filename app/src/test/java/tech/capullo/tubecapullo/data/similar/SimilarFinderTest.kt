package tech.capullo.tubecapullo.data.similar

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.tubecapullo.data.settings.AppSettings
import tech.capullo.tubecapullo.data.youtube.YtVideo

class SimilarFinderTest {
    private val online = MutableStateFlow(true)
    private val depth = MutableStateFlow(SimilarDepth.FAMILIAR)
    private val personalKey = MutableStateFlow("")
    private val settings = mockk<AppSettings> {
        every { similarOnline } returns online
        every { similarDepth } returns depth
        every { lastfmApiKey } returns personalKey
    }
    private val lastfm = mockk<LastfmClient>()
    private val resolver = mockk<TrackResolver>()

    private fun finder(builtIn: String = BUILT_IN) = LastfmSimilarFinder(lastfm, resolver, settings, builtIn)

    private val creep = YtVideo("XFkzRNyygfk", "Radiohead - Creep", "Radiohead", 239, null)

    private fun names(n: Int, artist: String = "A") = (1..n).map { SimilarTrack(artist, "Song $it", 1.0 - it / 100.0) }

    private fun video(title: String) = YtVideo(title.replace(" ", "").padEnd(11, 'x').take(11), "X - $title", "X", 200, null)

    @Test
    fun `switched off, Last fm is never called`() = runTest {
        online.value = false
        assertEquals(SimilarNames(emptyList(), "off"), finder().names(creep))
        coVerify(exactly = 0) { lastfm.similarTracks(any(), any(), any(), any()) }
    }

    @Test
    fun `with no built-in key and no personal key, Last fm is never called`() = runTest {
        assertEquals("no-key", finder(builtIn = "").names(creep).via)
        coVerify(exactly = 0) { lastfm.similarTracks(any(), any(), any(), any()) }
    }

    @Test
    fun `a personal key replaces the built-in key`() = runTest {
        personalKey.value = PERSONAL
        coEvery { lastfm.similarTracks(any(), any(), any(), any()) } returns names(10)
        finder().names(creep)
        coVerify { lastfm.similarTracks("Radiohead", "Creep", any(), PERSONAL) }
        coVerify(exactly = 0) { lastfm.similarTracks(any(), any(), any(), BUILT_IN) }
        assertEquals(PERSONAL, lastfmKeyOf("  $PERSONAL ", BUILT_IN))
        assertEquals(BUILT_IN, lastfmKeyOf("   ", BUILT_IN))
    }

    @Test
    fun `an unknown title is retried bare, and the answer says so`() = runTest {
        val despacito = YtVideo("kJQP7kiw5Fk", "Luis Fonsi - Despacito ft. Daddy Yankee", "Luis Fonsi", 282, null)
        coEvery { lastfm.similarTracks("Luis Fonsi", "Despacito ft. Daddy Yankee", any(), any()) } returns emptyList()
        coEvery { lastfm.similarTracks("Luis Fonsi", "Despacito", any(), any()) } returns names(30)

        val found = finder().names(despacito)

        assertEquals("retry", found.via)
        assertEquals(30, found.tracks.size)
    }

    @Test
    fun `a song Last fm does not know falls back to similar artists' top songs, one artist per round`() = runTest {
        coEvery { lastfm.similarTracks(any(), any(), any(), any()) } returns emptyList()
        coEvery { lastfm.similarArtists("Radiohead", any(), any()) } returns
            listOf(SimilarArtist("Muse", 0.9), SimilarArtist("Pixies", 0.8))
        coEvery { lastfm.topTracks("Muse", any(), any()) } returns names(3, "Muse")
        coEvery { lastfm.topTracks("Pixies", any(), any()) } returns names(3, "Pixies")

        val found = finder().names(creep)

        assertEquals("artist", found.via)
        assertEquals(listOf("Muse", "Pixies", "Muse", "Pixies", "Muse", "Pixies"), found.tracks.map { it.artist })
        assertEquals(0.9, found.tracks.first().match, 0.0)
    }

    @Test
    fun `a label upload Last fm does not know gets no artist fallback`() = runTest {
        val label = YtVideo("Umqb9KENgmk", "\"Tum Hi Ho\" Aashiqui 2 Full Song With Lyrics", "T-Series", 262, null)
        coEvery { lastfm.similarTracks(any(), any(), any(), any()) } returns emptyList()

        assertEquals(SimilarNames(emptyList(), "none"), finder().names(label))
        coVerify(exactly = 0) { lastfm.similarArtists(any(), any(), any()) }
    }

    @Test
    fun `a Last fm failure is an empty answer, not a crash`() = runTest {
        coEvery { lastfm.similarTracks(any(), any(), any(), any()) } throws LastfmException(29, "Rate Limit Exceeded")
        assertEquals(SimilarNames(emptyList(), "error"), finder().names(creep))
    }

    @Test
    fun `the Depth setting orders the names`() = runTest {
        depth.value = SimilarDepth.DEEP
        coEvery { lastfm.similarTracks(any(), any(), any(), any()) } returns names(30)
        val found = finder().names(creep)
        assertEquals("Song ${DEPTH_FAMILIAR_RANKS + 1}", found.tracks.first().title)
    }

    @Test
    fun `songs already in the Mix or related are skipped before any YouTube search`() = runTest {
        val tracks = listOf(SimilarTrack("X", "Known", 1.0), SimilarTrack("X", "Fresh", 0.9))
        coEvery { resolver.resolve("X", "Fresh") } returns Resolved(video("Fresh"), searched = true)

        val found = finder().resolve(SimilarNames(tracks, "track"), emptySet(), setOf("known"), count = 2)

        assertEquals(listOf("X - Fresh"), found.videos.map { it.title })
        assertEquals(1, found.skippedKnown)
        coVerify(exactly = 0) { resolver.resolve("X", "Known") }
    }

    @Test
    fun `resolving stops at the wanted count`() = runTest {
        coEvery { resolver.resolve(any(), any()) } answers { Resolved(video(secondArg()), searched = true) }

        val found = finder().resolve(SimilarNames(names(20), "track"), emptySet(), emptySet(), count = 4)

        assertEquals(4, found.videos.size)
        assertEquals(4, found.searched)
        coVerify(exactly = 4) { resolver.resolve(any(), any()) }
    }

    @Test
    fun `YouTube searches are bounded when names do not resolve`() = runTest {
        coEvery { resolver.resolve(any(), any()) } returns Resolved(null, searched = true)

        val found = finder().resolve(SimilarNames(names(40), "track"), emptySet(), emptySet(), count = 3)

        assertTrue(found.videos.isEmpty())
        assertEquals(3 * LastfmSimilarFinder.RESOLVE_ATTEMPTS_PER_PICK, found.searched)
    }

    @Test
    fun `cached misses cost no search, so more names may be tried`() = runTest {
        coEvery { resolver.resolve(any(), any()) } answers {
            val n = secondArg<String>().removePrefix("Song ").toInt()
            if (n <= 10) Resolved(null, searched = false) else Resolved(video(secondArg()), searched = true)
        }

        val found = finder().resolve(SimilarNames(names(20), "track"), emptySet(), emptySet(), count = 2)

        assertEquals(listOf("X - Song 11", "X - Song 12"), found.videos.map { it.title })
    }

    @Test
    fun `a video already played or queued, or two names on one upload, count once`() = runTest {
        val shared = video("Shared")
        coEvery { resolver.resolve(any(), any()) } answers {
            when (secondArg<String>()) {
                "Song 1" -> Resolved(video("Played"), searched = true)
                else -> Resolved(shared, searched = true)
            }
        }

        val found = finder().resolve(SimilarNames(names(4), "track"), setOf(video("Played").videoId), emptySet(), count = 3)

        assertEquals(listOf(shared), found.videos)
    }

    @Test
    fun `a failing lookup is skipped and the rest still resolve`() = runTest {
        coEvery { resolver.resolve(any(), "Song 1") } throws IllegalStateException("bot check")
        coEvery { resolver.resolve(any(), "Song 2") } returns Resolved(video("Two"), searched = true)

        val found = finder().resolve(SimilarNames(names(2), "track"), emptySet(), emptySet(), count = 2)

        assertEquals(listOf("X - Two"), found.videos.map { it.title })
    }

    @Test
    fun `key shape accepts a Last fm key and rejects the rest`() {
        assertTrue(isLastfmKeyShape(BUILT_IN))
        assertEquals(false, isLastfmKeyShape(BUILT_IN.uppercase()))
        assertEquals(false, isLastfmKeyShape(BUILT_IN.drop(1)))
    }

    private companion object {
        const val BUILT_IN = "0123456789abcdef0123456789abcdef"
        const val PERSONAL = "fedcba9876543210fedcba9876543210"
    }
}
