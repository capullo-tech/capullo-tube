package tech.capullo.tubecapullo.data.youtube

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import tech.capullo.tubecapullo.station.passesRelatedGate
import tech.capullo.tubecapullo.ui.home.artistName
import tech.capullo.tubecapullo.ui.home.hasAlbumTitle
import java.util.Locale
import kotlin.math.ceil

/**
 * The Home v2 probe: for liked-style seeds, how many related items are long (25 to 120 minutes),
 * how many look like albums (title or chapters), how many have chapters, and what the fallback
 * search "<artist> full album" adds. Network-gated behind `YT_SMOKE=1`:
 *
 * ```
 * YT_SMOKE=1 ./gradlew :app:testDebugUnitTest --tests '*HomeV2AlbumsProbeTest*'
 * ```
 *
 * **The criteria and the rules that turn the data into constants were written first**, in
 * `docs/probes/home-v2-criteria.md` section 3. Only Q1 asserts. The rest are measurements,
 * printed as `PROBE` lines and recorded in `docs/probes/home-v2-results.md`. It uses the app's
 * own definitions ([YouTubeExtractorClient.relatedStreams], [YouTubeExtractorClient.album],
 * [hasAlbumTitle], [passesRelatedGate]), so it measures what ships. The compilation words are
 * the criteria's own list ([hasCompilationWords]); the album test that shipped does not use them
 * (Q4 kept the chapter test as it is).
 */
class HomeV2AlbumsProbeTest {

    private lateinit var client: YouTubeExtractorClient

    @Before
    fun setUp() {
        assumeTrue("set YT_SMOKE=1 to run the probe", System.getenv("YT_SMOKE") == "1")
        client = probeClient()
    }

    /** One long-ish item (15 to 150 min) and its chapters; [chapters] null when the fetch
     *  failed. */
    private class Item(
        val rank: Int,
        val video: YtVideo,
        val chapters: List<YtChapter>?,
        val gate: Boolean,
    ) {
        val minutes: Double get() = video.durationSeconds / 60.0
        val long: Boolean get() = video.durationSeconds in LONG_MIN..LONG_MAX
        val albumTitle: Boolean get() = hasAlbumTitle(video.title)
        val hasChapters: Boolean get() = !chapters.isNullOrEmpty()
        val compilation: Boolean get() = hasCompilationWords(video.title)
    }

    private class Seed(
        val spec: String,
        val video: YtVideo,
        val relatedCount: Int,
        /** Rank 1..n of every related item, and its duration. */
        val ranks: List<Pair<Int, Long>>,
        val related: List<Item>,
        val fallbackQuery: String,
        val fallback: List<Item>,
    )

    @Test
    fun `q1-q6 full albums across liked-style seeds`() = runBlocking {
        val q1Failures = mutableListOf<String>()
        val seeds = mutableListOf<Seed>()
        SEEDS.forEach { spec ->
            val seed = runCatching { measure(spec) }
                .onFailure { q1Failures += "$spec threw ${it::class.simpleName}: ${it.message}" }
                .getOrNull() ?: return@forEach
            if (seed.relatedCount < MIN_RELATED) {
                q1Failures += "$spec listed ${seed.relatedCount} related (<$MIN_RELATED)"
            }
            seeds += seed
            printSeed(seed)
        }
        summarise(seeds)
        assertTrue("Q1 FAIL: $q1Failures", q1Failures.isEmpty())
    }

    private suspend fun measure(spec: String): Seed {
        val seed = client.search(spec, limit = 1).firstOrNull() ?: error("no search result")
        val related = client.relatedStreams(seed.videoId)
        val ranks = related.videos.mapIndexed { i, v -> (i + 1) to v.durationSeconds }
        val items = related.videos.mapIndexedNotNull { i, v ->
            if (v.durationSeconds !in WIDE_MIN..WIDE_MAX) return@mapIndexedNotNull null
            Item(i + 1, v, chaptersOf(v.videoId), passesRelatedGate(v, related.seedViews))
        }
        val query = "${artistName(seed.uploader ?: spec)} full album"
        val fallback = runCatching { client.search(query, limit = FALLBACK_RESULTS) }
            .getOrElse {
                println("PROBE fallback-error seed=${seed.videoId} error=\"${it.message}\"")
                emptyList()
            }
            .mapIndexedNotNull { i, v ->
                if (v.durationSeconds !in WIDE_MIN..WIDE_MAX) return@mapIndexedNotNull null
                Item(i + 1, v, chaptersOf(v.videoId), passesRelatedGate(v, -1))
            }
        return Seed(spec, seed, related.videos.size, ranks, items, query, fallback)
    }

    private suspend fun chaptersOf(id: String): List<YtChapter>? = runCatching { client.album(id).chapters }
        .onFailure { println("PROBE chapters-error id=$id error=\"${it.message}\"") }
        .getOrNull()

    private fun printSeed(seed: Seed) {
        println(
            "PROBE seed spec=\"${seed.spec}\" id=${seed.video.videoId} " +
                "uploader=\"${seed.video.uploader}\" related=${seed.relatedCount} " +
                "long=${seed.ranks.count { (_, d) -> d in LONG_MIN..LONG_MAX }} " +
                "fallback=\"${seed.fallbackQuery}\"",
        )
        (seed.related.map { "related" to it } + seed.fallback.map { "fallback" to it })
            .forEach { (list, item) -> printItem(seed, list, item) }
    }

    private fun printItem(seed: Seed, list: String, item: Item) {
        val chapters = item.chapters
        val shortest = chapters?.takeIf { it.isNotEmpty() }?.let { shortestChapterSeconds(it, item.video) }
        println(
            "PROBE item seed=${seed.video.videoId} list=$list rank=${item.rank} " +
                "id=${item.video.videoId} min=${fmt(item.minutes)} long=${item.long} " +
                "albumTitle=${item.albumTitle} compilation=${item.compilation} " +
                "chapters=${chapters?.size ?: "error"} shortestChapterS=${shortest ?: "-"} " +
                "verified=${item.video.uploaderVerified} views=${item.video.viewCount} " +
                "gate=${item.gate} uploader=\"${item.video.uploader}\" " +
                "title=\"${item.video.title}\"",
        )
    }

    private fun summarise(seeds: List<Seed>) {
        // Q2: long related items, pooled and per seed, ranks 1-8 and 9+.
        val allRanks = seeds.flatMap { it.ranks }
        fun longShare(ranks: List<Pair<Int, Long>>): String {
            val long = ranks.count { (_, d) -> d in LONG_MIN..LONG_MAX }
            return "$long/${ranks.size} (${share(long, ranks.size)})"
        }
        println(
            "PROBE q2 related=${allRanks.size} long=${longShare(allRanks)} " +
                "rank1to8=${longShare(allRanks.filter { it.first <= TOP_K })} " +
                "rank9up=${longShare(allRanks.filter { it.first > TOP_K })} " +
                "perSeedLong=${seeds.map { s -> s.ranks.count { (_, d) -> d in LONG_MIN..LONG_MAX } }}",
        )

        // Q3: the duration window, from album-titled items 15 to 150 minutes.
        val everything = seeds.flatMap { it.related + it.fallback }
        val population = everything.filter { it.albumTitle }.map { it.minutes }
        val inside = population.count { it * 60 >= LONG_MIN && it * 60 <= LONG_MAX }
        val q3Conclusive = population.size >= MIN_WINDOW_POPULATION
        val keep = population.isNotEmpty() && inside >= KEEP_SHARE * population.size
        val p10 = percentile(population, P10)
        val p90 = percentile(population, P90)
        val window = when {
            !q3Conclusive || keep -> "25..120 (kept)"
            else -> "${floor5(p10!!)}..${ceil5(p90!!)}"
        }
        println(
            "PROBE q3 population=${population.size} inside25to120=$inside " +
                "share=${share(inside, population.size)} p10=${p10?.let(::fmt)} " +
                "p90=${p90?.let(::fmt)} conclusive=$q3Conclusive window=$window " +
                "durations=${population.sorted().map(::fmt)}",
        )

        // Q4: album-ness among long items (25 to 120 min), related and fallback.
        val long = everything.filter { it.long && it.chapters != null }
        val titled = long.count { it.albumTitle }
        val chaptered = long.count { it.hasChapters }
        val both = long.count { it.albumTitle && it.hasChapters }
        val chaptersOnly = long.filter { it.hasChapters && !it.albumTitle }
        val chaptersOnlyCompilation = chaptersOnly.count { it.compilation }
        val q4Conclusive = chaptersOnly.size >= MIN_CHAPTERS_ONLY
        val compilationFilter = q4Conclusive && chaptersOnlyCompilation * 2 > chaptersOnly.size
        println(
            "PROBE q4 long=${long.size} albumTitle=$titled chapters=$chaptered both=$both " +
                "chaptersOnly=${chaptersOnly.size} chaptersOnlyCompilation=" +
                "$chaptersOnlyCompilation conclusive=$q4Conclusive " +
                "chapterNeedsNoCompilationWords=$compilationFilter",
        )
        chaptersOnly.forEach {
            println(
                "PROBE q4 chaptersOnly id=${it.video.videoId} compilation=${it.compilation} " +
                    "title=\"${it.video.title}\"",
            )
        }
        fun albumLike(item: Item): Boolean = item.albumTitle ||
            (item.hasChapters && !(compilationFilter && item.compilation))

        // Q5: candidates per seed (window, album-ness, gate).
        fun candidates(items: List<Item>) = items.count { it.long && it.gate && albumLike(it) }
        val fromRelated = seeds.map { candidates(it.related) }
        val fromFallback = seeds.map { candidates(it.fallback) }
        val median = percentile(fromRelated.map { it.toDouble() }, MEDIAN) ?: 0.0
        val seedCount = if (median <= 0.0) {
            MAX_SEEDS
        } else {
            (MIN_SEEDS..MAX_SEEDS).firstOrNull { it * median >= MIN_CARDS } ?: MAX_SEEDS
        }
        val allCandidates = seeds.flatMap { s -> (s.related + s.fallback).filter { it.long && it.gate && albumLike(it) } }
        val byChaptersOnly = allCandidates.count { !it.albumTitle }
        println(
            "PROBE q5 related=$fromRelated mean=${fmt(fromRelated.average())} " +
                "min=${fromRelated.minOrNull()} median=${fmt(median)} max=${fromRelated.maxOrNull()} " +
                "fallback=$fromFallback mean=${fmt(fromFallback.average())} " +
                "FULL_ALBUM_SEEDS=$seedCount",
        )
        val gatedDropped = seeds.flatMap { it.related }.count { it.long && albumLike(it) && !it.gate }
        println("PROBE q5 relatedAlbumLikeDroppedByGate=$gatedDropped")

        // Q4b: chapter checks per load.
        val unchecked = seeds.map { s -> s.related.count { it.long && it.gate && !it.albumTitle } }
        val uncheckedMedian = percentile(unchecked.map { it.toDouble() }, MEDIAN) ?: 0.0
        val skip = byChaptersOnly * 10 <= allCandidates.size
        val checks = if (skip) 0 else minOf(ceil(uncheckedMedian * seedCount).toInt(), MAX_CHECKS)
        println(
            "PROBE q4b noTitleGatedLongPerSeed=$unchecked median=${fmt(uncheckedMedian)} " +
                "candidates=${allCandidates.size} byChaptersOnly=$byChaptersOnly skip=$skip " +
                "FULL_ALBUM_CHAPTER_CHECKS=$checks",
        )

        // Q6: chapter coverage.
        val titledLong = long.filter { it.albumTitle }
        println(
            "PROBE q6 albumTitledWithChapters=${titledLong.count { it.hasChapters }}/" +
                "${titledLong.size} (${share(titledLong.count { it.hasChapters }, titledLong.size)}) " +
                "longWithChapters=$chaptered/${long.size} (${share(chaptered, long.size)})",
        )
        val counts = long.filter { it.hasChapters }.map { it.chapters!!.size.toDouble() }
        val shortest = long.filter { it.hasChapters }
            .map { shortestChapterSeconds(it.chapters!!, it.video).toDouble() }
        println(
            "PROBE q6 chapterCounts min=${counts.minOrNull()?.let(::fmt)} " +
                "median=${percentile(counts, MEDIAN)?.let(::fmt)} max=${counts.maxOrNull()?.let(::fmt)} " +
                "shortestChapterS min=${shortest.minOrNull()?.let(::fmt)} " +
                "median=${percentile(shortest, MEDIAN)?.let(::fmt)}",
        )
    }

    private companion object {
        /** The seeds of the criteria doc, section 3: search queries whose first video result is
         *  the seed. */
        val SEEDS = listOf(
            "Radiohead Karma Police",
            "Fleetwood Mac Dreams",
            "Miles Davis So What",
            "Daft Punk Get Lucky",
            "Rammstein Du Hast",
            "Burna Boy Last Last",
            "Buena Vista Social Club Chan Chan",
            "Fairuz Kifak Inta",
            "Ichiko Aoba Asleep Among Endives",
            "Tame Impala The Less I Know The Better",
        )

        const val MIN_RELATED = 15
        const val TOP_K = 8

        /** The PLAN's long window (GUESS) the criteria measure against, seconds. */
        const val LONG_MIN = 25 * 60L
        const val LONG_MAX = 120 * 60L

        /** The wider band whose chapters the probe fetches, seconds. */
        const val WIDE_MIN = 15 * 60L
        const val WIDE_MAX = 150 * 60L

        const val FALLBACK_RESULTS = 20

        const val MIN_WINDOW_POPULATION = 15
        const val KEEP_SHARE = 0.8
        const val MIN_CHAPTERS_ONLY = 10
        const val MIN_CARDS = 4
        const val MIN_SEEDS = 2
        const val MAX_SEEDS = 5
        const val MAX_CHECKS = 8
        const val P10 = 0.10
        const val P90 = 0.90
        const val MEDIAN = 0.50

        fun shortestChapterSeconds(chapters: List<YtChapter>, video: YtVideo): Long {
            val ends = chapters.drop(1).map { it.startMs / 1000 } + video.durationSeconds
            return chapters.zip(ends).minOf { (c, end) -> end - c.startMs / 1000 }
        }

        fun percentile(values: List<Double>, p: Double): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        }

        fun floor5(minutes: Double): Int = (minutes / 5).toInt() * 5

        fun ceil5(minutes: Double): Int = ceil(minutes / 5).toInt() * 5

        fun share(part: Int, whole: Int): String = if (whole == 0) "-" else "%.2f".format(Locale.ROOT, part.toDouble() / whole)

        fun fmt(value: Double): String = "%.1f".format(Locale.ROOT, value)

        /** The criteria's "Compilation words". */
        val COMPILATION_WORD = Regex(
            """(?<![\p{L}\p{N}])(mix|playlist|best of|greatest hits|compilation|non-?stop|hours?|top|songs)""" +
                """(?![\p{L}\p{N}])""",
            RegexOption.IGNORE_CASE,
        )

        fun hasCompilationWords(title: String): Boolean = COMPILATION_WORD.containsMatchIn(title)
    }
}
