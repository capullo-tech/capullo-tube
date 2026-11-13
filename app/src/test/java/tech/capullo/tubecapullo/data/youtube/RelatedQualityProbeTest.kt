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

class RelatedQualityProbeTest {
    private lateinit var client: YouTubeExtractorClient

    @Before
    fun setUp() {
        assumeTrue("set YT_SMOKE=1 to run the probe", System.getenv("YT_SMOKE") == "1")
        client = probeClient()
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
        assertTrue("Q1 FAIL: $q1Failures", q1Failures.isEmpty())
    }

    private class Seed(
        val spec: String,
        val id: String,
        val info: StreamInfo,
        val related: List<StreamInfoItem>,
        val mix: List<YtVideo>,
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

    private class Gate(val minViews: Long, val fraction: Double) {
        fun keeps(item: StreamInfoItem, seedViews: Long): Boolean {
            if (item.isUploaderVerified) return true
            val views = item.viewCount
            if (views < 0) return false
            return views >= minViews && (seedViews < 0 || views >= fraction * seedViews)
        }
    }

    private companion object {
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

        const val INIT_QUERY = "music"

        const val TOP_K = 8

        const val GUESS_MIN_VIEWS = 10_000L
        const val GUESS_FRACTION = 0.01

        const val MIN_REFERENCE = 20
        const val MIN_SMALL_HALF = 8
        const val P10 = 0.10
        const val MEDIAN = 0.50

        fun idOf(item: StreamInfoItem): String = YouTubeUrls.parseVideoId(item.url.orEmpty()) ?: "?"

        fun titleKey(item: StreamInfoItem): String? = TitleCleaner.key(item.name.orEmpty(), item.uploaderName)

        fun sameKey(a: String?, b: String?): Boolean = a != null && a == b

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

        fun stepAtOrBelow(value: Double): Double {
            val decade = 10.0.pow(floor(log10(value)))
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
