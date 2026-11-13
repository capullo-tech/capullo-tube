package tech.capullo.tubecapullo.station

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtVideo
import kotlin.random.Random

class PairsTest {
    private fun video(
        id: String,
        title: String = "t-$id",
        uploader: String = "u",
        views: Long = -1,
        verified: Boolean = false,
        seconds: Long = 240,
    ) = YtVideo(
        videoId = id,
        title = title,
        uploader = uploader,
        durationSeconds = seconds,
        thumbnailUrl = null,
        viewCount = views,
        uploaderVerified = verified,
    )

    private fun related(id: String, title: String = "t-$id", views: Long = -1) = video(id, title, views = views, verified = true)

    private val seed = video("seed")

    private fun related(vararg videos: YtVideo) = YtRelated(seedViews = 1_000_000, videos = videos.toList())

    @Test
    fun `pairs hold one of each list, each list keeps its order, then the longer list's rest`() {
        val mix = (1..5).map { "m$it" }
        val rel = (1..3).map { "r$it" }

        val merged = pairUp(mix, rel, Random(1))

        assertEquals(8, merged.size)
        merged.take(6).chunked(2).forEachIndexed { i, pair ->
            assertEquals("pair $i: $merged", setOf(mix[i], rel[i]), pair.toSet())
        }
        assertEquals(listOf("m4", "m5"), merged.drop(6))
        assertEquals(listOf("r2", "r3"), pairUp(listOf("m1"), rel, Random(1)).drop(2))
    }

    @Test
    fun `which list leads a pair comes from the seeded random, and both orders occur`() {
        val mix = (1..20).map { "m$it" }
        val rel = (1..20).map { "r$it" }

        val first = pairUp(mix, rel, Random(7))

        assertEquals(first, pairUp(mix, rel, Random(7)))
        val leads = first.chunked(2).map { it.first().first() }
        assertTrue("Mix never led: $first", 'm' in leads)
        assertTrue("related never led: $first", 'r' in leads)
        assertNotEquals("another seed, another order", first, pairUp(mix, rel, Random(8)))
    }

    @Test
    fun `when one list is empty the other fills every place, in its own order`() {
        val mix = listOf("m1", "m2", "m3")
        assertEquals(mix, pairUp(mix, emptyList(), Random(1)))
        assertEquals(mix, pairUp(emptyList(), mix, Random(1)))
    }

    @Test
    fun `the gate keeps a verified upload, and an unverified one only above both floors`() {
        val seedViews = RELATED_MIN_VIEWS * 1_000
        val share = (RELATED_MIN_SEED_FRACTION * seedViews).toLong()

        assertTrue(passesRelatedGate(video("v", views = -1, verified = true), seedViews))
        assertFalse("unknown views", passesRelatedGate(video("u", views = -1), seedViews))
        assertTrue(passesRelatedGate(video("a", views = share), seedViews))
        assertFalse("under the seed share", passesRelatedGate(video("b", views = share - 1), seedViews))

        val smallSeed = RELATED_MIN_VIEWS
        assertTrue(passesRelatedGate(video("c", views = RELATED_MIN_VIEWS), smallSeed))
        assertFalse(
            "under the floor",
            passesRelatedGate(video("d", views = RELATED_MIN_VIEWS - 1), smallSeed),
        )
        assertTrue(
            "unknown seed views: the floor alone",
            passesRelatedGate(video("e", views = RELATED_MIN_VIEWS), seedViews = -1),
        )
    }

    @Test
    fun `every related video is taken, however deep its rank, and the gate counts them all`() {
        val low = (1..12).map { video("low$it", views = 0) }
        val deep = (1..8).map { related("deep$it") }

        val lists = songLists(seed, emptyList(), related(*(low + deep).toTypedArray()), emptySet())

        assertEquals("ranks 13 to 20 kept", deep, lists.related)
        assertEquals(deep.size, lists.gateKept)
        assertEquals(low.size, lists.gateDropped)
    }

    @Test
    fun `a related upload of the seed's own song is dropped`() {
        val rick = video(
            "seed",
            "Rick Astley - Never Gonna Give You Up (Official Music Video)",
            uploader = "Rick Astley",
        )
        val lyrics = related("lyr", "NEVER GONNA GIVE YOU UP (Lyrics)")
        val other = related("oth", "Together Forever")

        val lists = songLists(rick, emptyList(), related(lyrics, other), emptySet())

        assertEquals(listOf(other), lists.related)
    }

    @Test
    fun `of tracks with one title only the most viewed stays, whichever list it is in`() {
        val mixCopy = video("mixcopy", "Song A (Official Audio)")
        val relatedCopy = related("relcopy", "song a [Official Video]", views = 5_000_000)
        val first = video("first", "Song B")
        val tie = video("tie", "Song B!")
        val mix = listOf(mixCopy, first, tie, video("m3"))

        val lists = songLists(seed, mix, related(relatedCopy, related("r2")), emptySet())

        assertEquals(listOf(first, video("m3")), lists.mix)
        assertEquals(listOf(relatedCopy, related("r2")), lists.related)
        assertEquals(2, lists.dupes)
    }

    @Test
    fun `the seed, excluded ids, repeats and tracks outside the window never pass`() {
        val both = video("both")
        val mix = listOf(
            seed,
            video("played"),
            both,
            video("short", seconds = StationEngine.MIN_TRACK_SECONDS - 1),
            video("long", seconds = StationEngine.MAX_TRACK_SECONDS + 1),
            video("ok"),
        )
        val rel = related(related("both"), related("played"), related("relok"))

        val lists = songLists(seed, mix, rel, excludedIds = setOf("played"))

        assertEquals("an id in both lists stays in the Mix", listOf(both, video("ok")), lists.mix)
        assertEquals(listOf(related("relok")), lists.related)
    }
}
