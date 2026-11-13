package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

/**
 * The region probe (PLAN item 14): does the content country (`gl`) change Mix and related for
 * the same seed? Network-gated behind `YT_SMOKE=1`, like the RD-mix probe:
 *
 * ```
 * YT_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*RegionMixProbeTest*'
 * ```
 *
 * **Pass criteria were written first**, in `docs/probes/region-mix-criteria.md` - read that
 * before this. Only Q1 (every fetch works, the country applied) asserts. Q2-Q4 print within,
 * cross and gap with every pair value; a reader judges them against the criteria bands and
 * records the run in `docs/probes/region-mix-results.md`. A measurement that asserts its own
 * expected value stops being a measurement.
 *
 * The country is switched through NewPipe's global preference, the same one
 * [YouTubeExtractorClient] pins at init, so the probe measures exactly what a device in each
 * country would send. Only `gl` moves: the language stays [PROBE_LANGUAGE] throughout.
 */
class RegionMixProbeTest {

    private lateinit var client: YouTubeExtractorClient

    // Why these three (catalogue age, language): see the criteria doc's Method section.
    private val seeds = listOf("dQw4w9WgXcQ", "kJQP7kiw5Fk", "JGwWNGJdvx8")

    @Before
    fun setUp() {
        assumeTrue("set YT_SMOKE=1 to run the region probe", System.getenv("YT_SMOKE") == "1")
        client = probeClient()
        // The client pins NewPipe's global country from the JVM locale on its FIRST call
        // (ensureInitialized). Spend that call here, so the per-country overrides below stick.
        runBlocking { client.search(CONTROL_QUERY, limit = 1) }
    }

    @Test
    fun `q1-q3 mix and related under two countries`() = runBlocking {
        val q1Failures = mutableListOf<String>()
        seeds.forEach { seed ->
            val mix = fetchFour { client.mix(seed).ids() }
            report("q2 mix", seed, mix)
            q1Failures += mix.tooSmall("mix $seed", MIN_MIX_IDS)

            val related = fetchFour {
                client.relatedStreams(seed).videos.ids()
            }
            report("q3 related", seed, related)
            q1Failures += related.tooSmall("related $seed", MIN_RELATED_IDS)
        }
        // After the prints, so one short fetch does not hide the other seeds' numbers.
        assertTrue("Q1 FAIL: $q1Failures", q1Failures.isEmpty())
    }

    @Test
    fun `q4 control - search under two countries - measurement only`() = runBlocking {
        val search = fetchFour { client.search(CONTROL_QUERY).ids() }
        report("q4 control", "\"$CONTROL_QUERY\"", search)
    }

    /** One subject's four fetches, A-1, B-1, A-2, B-2 in that order, so time drift between
     *  fetches lands on both countries alike. */
    private class Fetches(
        val a1: Set<String>,
        val b1: Set<String>,
        val a2: Set<String>,
        val b2: Set<String>,
    ) {
        /** Same country fetched twice: the noise floor. */
        val within = listOf(overlap(a1, a2), overlap(b1, b2))

        /** Every A-versus-B pair. */
        val cross = listOf(overlap(a1, b1), overlap(a2, b2), overlap(a1, b2), overlap(a2, b1))

        private val labelled = listOf(
            "$COUNTRY_A-1" to a1,
            "$COUNTRY_B-1" to b1,
            "$COUNTRY_A-2" to a2,
            "$COUNTRY_B-2" to b2,
        )

        val sizes = labelled.joinToString(" ") { (label, ids) -> "$label=${ids.size}" }

        fun tooSmall(what: String, min: Int): List<String> = labelled
            .filter { (_, ids) -> ids.size < min }
            .map { (label, ids) -> "$what $label returned ${ids.size} (<$min)" }
    }

    private suspend fun fetchFour(fetch: suspend () -> Set<String>) = Fetches(
        a1 = inCountry(COUNTRY_A, fetch),
        b1 = inCountry(COUNTRY_B, fetch),
        a2 = inCountry(COUNTRY_A, fetch),
        b2 = inCountry(COUNTRY_B, fetch),
    )

    private suspend fun inCountry(code: String, fetch: suspend () -> Set<String>): Set<String> {
        NewPipe.setupLocalization(PROBE_LANGUAGE, ContentCountry(code))
        assertEquals(
            "Q1 FAIL: country $code not applied",
            code,
            ServiceList.YouTube.contentCountry.countryCode,
        )
        return fetch()
    }

    private fun report(question: String, subject: String, fetches: Fetches) {
        val within = fetches.within.average()
        val cross = fetches.cross.average()
        println(
            "PROBE $question subject=$subject ${fetches.sizes} " +
                "within=${fetches.within.map { fmt(it) }} mean=${fmt(within)} " +
                "cross=${fetches.cross.map { fmt(it) }} mean=${fmt(cross)} " +
                "gap=${fmt(within - cross)}",
        )
    }

    private fun List<YtVideo>.ids() = map { it.videoId }.toSet()

    private companion object {
        /** The two countries compared. Both are in the extractor's supported list; MX gives
         *  Spanish-language seeds the best chance to show an effect (criteria doc). */
        const val COUNTRY_A = "US"
        const val COUNTRY_B = "MX"

        /** Held fixed for both countries so only `gl` changes between fetches. */
        val PROBE_LANGUAGE = Localization("en", "US")

        /** Q1's floors, from the criteria doc. Mix's 10 matches the RD-mix probe's Q1. */
        const val MIN_MIX_IDS = 10
        const val MIN_RELATED_IDS = 5

        /** Q4's control query: generic, and a Spanish word, so a country effect on search has
         *  room to show. */
        const val CONTROL_QUERY = "musica"

        /** Shared IDs over the smaller set: the RD-mix probe's metric, so the two runs compare. */
        fun overlap(a: Set<String>, b: Set<String>): Double {
            val smaller = minOf(a.size, b.size)
            return if (smaller == 0) 0.0 else (a intersect b).size.toDouble() / smaller
        }

        fun fmt(value: Double) = "%.2f".format(Locale.ROOT, value)
    }
}
