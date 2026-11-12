package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.DateWrapper
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import tech.capullo.tubecapullo.util.TitleCleaner
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The related-quality probe: what YouTube's top related items look like (views, verified
 * uploaders, upload dates, same-song uploads), and the related gate's constants derived from
 * that. Network-gated behind `YT_SMOKE=1`, like the RD-mix probe:
 *
 * ```
 * YT_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*RelatedQualityProbeTest*'
 * ```
 *
 * **The criteria and the rule that turns the data into constants were written first**, in
 * `docs/probes/related-quality-criteria.md` - read that before this. Only Q1 (every fetch works)
 * asserts. Q2-Q6 are measurements, printed as `PROBE` lines and recorded in
 * `docs/probes/related-quality-results.md`. A measurement that asserts its own expected value
 * stops being a measurement.
 *
 * It reads the extractor's own item types (`StreamInfo`, `StreamInfoItem`) rather than going
 * through [YouTubeExtractorClient.relatedStreams], because it prints fields the app does not
 * keep (the date's approximation flag, YouTube's date text). The list is the same one
 * `relatedStreams` returns: the seed's `StreamInfo` related items that are video streams, in
 * YouTube's order.
 */
class RelatedQualityProbeTest {

    private lateinit var client: YouTubeExtractorClient

    @Before
    fun setUp() {
        assumeTrue("set YT_SMOKE=1 to run the probe", System.getenv("YT_SMOKE") == "1")
        client = probeClient()
        // The client initialises NewPipe on its FIRST call; the direct extractor calls below need
        // that done.
        runBlocking { client.search(INIT_QUERY, limit = 1) }
    }

    @Test
    fun `q1-q6 related quality across seeds`() = runBlocking {
        val q1Failures = mutableListOf<String>()
        val seeds = mutableListOf<Seed>()
        SEEDS.forEach { spec ->
            val seed = runCatching { measure(spec) }
                .onFailure { q1Failures += "$spec threw ${it::class.simpleName}: ${it.message}" }
                .getOrNull() ?: return@forEach
            if (seed.related.size < TOP_K) {
                q1Failures += "$spec listed ${seed.related.size} related (<$TOP_K)"
            }
            seeds += seed
            printSeed(seed)
        }
        summarise(seeds)
        // After the prints, so one failed seed does not hide the other seeds' numbers.
        assertTrue("Q1 FAIL: $q1Failures", q1Failures.isEmpty())
    }

    /** A seed and its fetched lists. */
    private class Seed(
        val spec: String,
        val id: String,
        val info: StreamInfo,
        /** The related video items, in YouTube's order (ranks 1..n). */
        val related: List<StreamInfoItem>,
        /** The Mix as the app fetches it (all pages), for the duplicate count. */
        val mix: List<YtVideo>,
        /** The Mix's first page as extractor items, for the metadata counts. */
        val mixItems: List<StreamInfoItem>,
    ) {
        val views: Long get() = info.viewCount
        val key: String? get() = TitleCleaner.key(info.name.orEmpty(), info.uploaderName)
        val top: List<StreamInfoItem> get() = related.take(TOP_K)
    }

    private suspend fun measure(spec: String): Seed {
        val id = if (spec.length == 11 && ' ' !in spec) {
            spec
        } else {
            client.search(spec, limit = 1).firstOrNull()?.videoId ?: error("no search result")
        }
        val info = StreamInfo.getInfo(ServiceList.YouTube, YouTubeUrls.watchUrl(id))
        val related = (info.relatedItems ?: emptyList())
            .filterIsInstance<StreamInfoItem>()
            .filter { it.streamType == StreamType.VIDEO_STREAM }
        val mix = client.mix(id)
        val mixItems = PlaylistInfo.getInfo(ServiceList.YouTube, YouTubeUrls.mixUrl(id))
            .relatedItems.filterIsInstance<StreamInfoItem>()
        return Seed(spec, id, info, related, mix, mixItems)
    }

    private fun printSeed(seed: Seed) {
        val info = seed.info
        println(
            "PROBE seed spec=\"${seed.spec}\" id=${seed.id} views=${seed.views} " +
                "verified=${info.isUploaderVerified} date=${date(info.uploadDate, null)} " +
                "dur=${info.duration} uploader=\"${info.uploaderName}\" " +
                "cleaned=\"${TitleCleaner.clean(info.name.orEmpty(), info.uploaderName)}\" " +
                "related=${seed.related.size} mix=${seed.mix.size} mixPage=${seed.mixItems.size}",
        )
        seed.related.forEachIndexed { i, item ->
            val ratio = if (item.viewCount >= 0 && seed.views > 0) {
                item.viewCount.toDouble() / seed.views
            } else {
                null
            }
            val looseMatch = looseKey(item.name.orEmpty()) == looseKey(info.name.orEmpty())
            println(
                "PROBE item seed=${seed.id} rank=${i + 1} id=${idOf(item)} " +
                    "views=${item.viewCount} " +
                    "ratio=${ratio?.let { "%.5f".format(Locale.ROOT, it) } ?: "-"} " +
                    "verified=${item.isUploaderVerified} " +
                    "date=${date(item.uploadDate, item.textualUploadDate)} dur=${item.duration} " +
                    "uploader=\"${item.uploaderName}\" " +
                    "cleaned=\"${TitleCleaner.clean(item.name.orEmpty(), item.uploaderName)}\" " +
                    "seedKey=${sameKey(titleKey(item), seed.key)} looseSeedKey=$looseMatch",
            )
        }
        val withViews = seed.mixItems.count { it.viewCount >= 0 }
        val withDate = seed.mixItems.count { it.uploadDate != null }
        println(
            "PROBE mix seed=${seed.id} page=${seed.mixItems.size} withViews=$withViews " +
                "withDate=$withDate",
        )
    }

    private fun summarise(seeds: List<Seed>) {
        val top = seeds.flatMap { seed -> seed.top.map { seed to it } }
        val known = top.filter { (_, item) -> item.viewCount >= 0 }
        val verified = known.filter { (_, item) -> item.isUploaderVerified }
        val unverified = known.filter { (_, item) -> !item.isUploaderVerified }
        println(
            "PROBE q2 top=${top.size} unknownViews=${top.size - known.size} " +
                "verified=${top.count { (_, item) -> item.isUploaderVerified }} " +
                "verifiedShare=" +
                share(top.count { (_, item) -> item.isUploaderVerified }, top.size),
        )
        val groups = listOf("all" to known, "verified" to verified, "unverified" to unverified)
        for ((label, group) in groups) {
            val views = group.map { (_, item) -> item.viewCount.toDouble() }
            val ratios = group.filter { (seed, _) -> seed.views > 0 }
                .map { (seed, item) -> item.viewCount.toDouble() / seed.views }
            println(
                "PROBE q2 group=$label n=${group.size} views=${quantiles(views)} " +
                    "ratio=${quantiles(ratios)}",
            )
        }
        seeds.forEach { seed ->
            val views = seed.top.filter { it.viewCount >= 0 }.map { it.viewCount.toDouble() }
            println(
                "PROBE q2 seed=${seed.id} seedViews=${seed.views} " +
                    "verified=${seed.top.count { it.isUploaderVerified }}/${seed.top.size} " +
                    "unknown=${seed.top.count { it.viewCount < 0 }} views=${quantiles(views)}",
            )
        }

        // Q3: the rule fixed in the criteria doc.
        val ratioP10 = percentile(
            verified.filter { (seed, _) -> seed.views > 0 }
                .map { (seed, item) -> item.viewCount.toDouble() / seed.views },
            P10,
        )
        val medianSeed = percentile(seeds.map { it.views.toDouble() }, MEDIAN)
        val smallHalf = verified.filter { (seed, _) -> seed.views <= (medianSeed ?: -1.0) }
        val viewsP10 = percentile(smallHalf.map { (_, item) -> item.viewCount.toDouble() }, P10)
        val conclusive = verified.size >= MIN_REFERENCE && smallHalf.size >= MIN_SMALL_HALF
        val fraction = ratioP10?.let { stepAtOrBelow(it) }
        val minViews = viewsP10?.let { stepAtOrBelow(it) }
        println(
            "PROBE q3 reference=${verified.size} smallHalf=${smallHalf.size} " +
                "medianSeedViews=${medianSeed?.toLong()} ratioP10=${ratioP10?.let { fmt(it) }} " +
                "viewsP10=${viewsP10?.toLong()} conclusive=$conclusive " +
                "RELATED_MIN_SEED_FRACTION=${fraction?.let { fmt(it) }} " +
                "RELATED_MIN_VIEWS=${minViews?.toLong()}",
        )

        // Q4: the gate on this run's own data, with the rule's constants and with the guesses.
        val gates = buildList {
            if (fraction != null && minViews != null) {
                add("rule" to Gate(minViews.toLong(), fraction))
            }
            add("guess" to Gate(GUESS_MIN_VIEWS, GUESS_FRACTION))
        }
        gates.forEach { (label, gate) ->
            seeds.forEach { seed ->
                val kept = seed.top.count { gate.keeps(it, seed.views) }
                println(
                    "PROBE q4 gate=$label seed=${seed.id} kept=$kept " +
                        "dropped=${seed.top.size - kept}",
                )
                seed.top.filterNot { gate.keeps(it, seed.views) }.forEach {
                    println(
                        "PROBE q4 gate=$label dropped seed=${seed.id} id=${idOf(it)} " +
                            "views=${it.viewCount} " +
                            "uploader=\"${it.uploaderName}\" title=\"${it.name}\"",
                    )
                }
            }
            val unverifiedTop = top.filter { (_, item) -> !item.isUploaderVerified }
            val droppedUnverified = unverifiedTop.count { (seed, item) ->
                !gate.keeps(item, seed.views)
            }
            val deep = seeds.flatMap { seed -> seed.related.drop(TOP_K).map { seed to it } }
            val keptTop = top.count { (seed, item) -> gate.keeps(item, seed.views) }
            val keptDeep = deep.count { (seed, item) -> gate.keeps(item, seed.views) }
            println(
                "PROBE q4 gate=$label minViews=${gate.minViews} fraction=${fmt(gate.fraction)} " +
                    "unverifiedDropped=$droppedUnverified/${unverifiedTop.size} " +
                    "flag=${droppedUnverified * 2 > unverifiedTop.size} " +
                    "keptRank1to$TOP_K=${share(keptTop, top.size)} " +
                    "keptRank${TOP_K + 1}up=${share(keptDeep, deep.size)} (n=${deep.size})",
            )
        }

        // Q5: same-song rules.
        val seedMatches = top.count { (seed, item) -> sameKey(titleKey(item), seed.key) }
        val looseSeedMatches = top.count { (seed, item) ->
            looseKey(item.name.orEmpty()) == looseKey(seed.info.name.orEmpty())
        }
        var batch = 0
        var duplicates = 0
        var looseDuplicates = 0
        seeds.forEach { seed ->
            val pool = seed.mix.drop(1).map { it.title to it.uploader } +
                seed.top.map { it.name.orEmpty() to it.uploaderName }
            batch += pool.size
            val seen = HashSet<String>()
            val looseSeen = HashSet<String>()
            pool.forEach { (title, uploader) ->
                val strict = TitleCleaner.key(title, uploader)?.let { !seen.add(it) } ?: false
                val loose = !looseSeen.add(looseKey(title))
                if (strict) duplicates++
                if (loose) looseDuplicates++
                if (strict || loose) {
                    val which = if (strict) "duplicate" else "looseOnly"
                    println(
                        "PROBE q5 $which seed=${seed.id} uploader=\"$uploader\" " +
                            "title=\"$title\"",
                    )
                }
            }
        }
        println(
            "PROBE q5 seedTitle=$seedMatches/${top.size} (${share(seedMatches, top.size)}) " +
                "looseSeedTitle=$looseSeedMatches/${top.size} " +
                "duplicates=$duplicates/$batch (${share(duplicates, batch)}) " +
                "looseDuplicates=$looseDuplicates/$batch (${share(looseDuplicates, batch)})",
        )

        // Q6: what the display line can show.
        val dated = top.filter { (_, item) -> item.uploadDate != null }
        val approximate = dated.count { (_, item) -> item.uploadDate!!.isApproximation }
        val mixItems = seeds.flatMap { it.mixItems }
        println(
            "PROBE q6 topWithViews=${share(known.size, top.size)} " +
                "topWithDate=${share(dated.size, top.size)} " +
                "approximate=$approximate/${dated.size} " +
                "mixWithViews=${share(mixItems.count { it.viewCount >= 0 }, mixItems.size)} " +
                "mixWithDate=${share(mixItems.count { it.uploadDate != null }, mixItems.size)} " +
                "(n=${mixItems.size})",
        )
    }

    /** The related gate as the criteria doc states it. */
    private class Gate(val minViews: Long, val fraction: Double) {
        fun keeps(item: StreamInfoItem, seedViews: Long): Boolean {
            if (item.isUploaderVerified) return true
            val views = item.viewCount
            if (views < 0) return false
            return views >= minViews && (seedViews < 0 || views >= fraction * seedViews)
        }
    }

    private companion object {
        /** The seeds of the criteria doc: three ids of earlier probes, then search queries
         *  whose first video result is the seed. */
        val SEEDS = listOf(
            "dQw4w9WgXcQ",
            "kJQP7kiw5Fk",
            "9bZkp7q19f0",
            "Burna Boy Last Last",
            "Aya Nakamura Djadja",
            "Miles Davis So What",
            "Rammstein Du Hast",
            "Arijit Singh Tum Hi Ho",
            "Buena Vista Social Club Chan Chan",
            "Fairuz Kifak Inta",
            "Mdou Moctar Afrique Victime",
            "Ichiko Aoba Asleep Among Endives",
        )

        /** The first search initialises NewPipe; any query does. */
        const val INIT_QUERY = "music"

        /** The top-8 cap this run measured (RELATED_TOP_K, removed 2026-11-12 for all related). */
        const val TOP_K = 8

        /** The starting GUESSES the rule replaces. */
        const val GUESS_MIN_VIEWS = 10_000L
        const val GUESS_FRACTION = 0.01

        /** Q3's thresholds and percentile, from the criteria doc. */
        const val MIN_REFERENCE = 20
        const val MIN_SMALL_HALF = 8
        const val P10 = 0.10
        const val MEDIAN = 0.50

        fun idOf(item: StreamInfoItem): String = YouTubeUrls.parseVideoId(item.url.orEmpty()) ?: "?"

        fun titleKey(item: StreamInfoItem): String? = TitleCleaner.key(item.name.orEmpty(), item.uploaderName)

        /** The code's rule: a null key (no letter or digit) never matches. */
        fun sameKey(a: String?, b: String?): Boolean = a != null && a == b

        /** The comparison-only loose key: no bracketed groups, the text after the last spaced
         *  dash, case-, space- and punctuation-blind. */
        fun looseKey(title: String): String {
            val bare = title.replace(Regex("""[(\[【][^)\]】]*[)\]】]"""), " ").substringBefore('|')
            val song = bare.split(Regex("""\s+[-–—]\s+""")).last()
            return song.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.ifEmpty { title }
        }

        fun date(date: DateWrapper?, text: String?): String {
            val year = date?.offsetDateTime()?.year?.toString() ?: "-"
            val approx = if (date?.isApproximation == true) "~" else ""
            return "$approx$year" + (text?.let { "(\"$it\")" } ?: "")
        }

        /** Nearest-rank percentile; null for no values. */
        fun percentile(values: List<Double>, p: Double): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        }

        fun quantiles(values: List<Double>): String {
            if (values.isEmpty()) return "[]"
            val q = listOf(0.0, P10, 0.25, MEDIAN).map { percentile(values, it)!! }
            return "[min=${fmt(values.min())} p10=${fmt(q[1])} p25=${fmt(q[2])} med=${fmt(q[3])}]"
        }

        /** The largest 1-2-5 step at or below [value] (> 0). */
        fun stepAtOrBelow(value: Double): Double {
            val decade = 10.0.pow(floor(log10(value)))
            // The tolerance keeps an exact step (0.01) from losing a decade to rounding.
            return listOf(5.0, 2.0, 1.0, 0.5).map { it * decade }.first { it <= value * (1 + 1e-9) }
        }

        fun share(part: Int, whole: Int): String = if (whole == 0) {
            "-"
        } else {
            "%.2f".format(Locale.ROOT, part.toDouble() / whole)
        }

        fun fmt(value: Double): String = if (value >= 1000) {
            value.toLong().toString()
        } else {
            "%.5f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
        }
    }
}
