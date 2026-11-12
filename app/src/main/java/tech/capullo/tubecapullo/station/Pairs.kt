package tech.capullo.tubecapullo.station

import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.util.TitleCleaner
import kotlin.random.Random

// Pairs (owner decision 2026-11-12): every fetch from a song takes BOTH its Mix (YouTube's order)
// and its related videos (YouTube's rank order), and queues them in pairs - one Mix track and
// one related track, which one first is random. The station (chains, Blend in, Liked radio,
// Switch now) and Home's "More like" rows share the rules below. Pass criteria:
// docs/probes/radio-criteria.md sections 2 and 14 to 16.
//
// Related: every video NewPipe returns per seed (about 20), not a top K (owner decision
// 2026-11-12, PLAN "Home v2 and albums"). The related-quality probe (2026-11-12,
// docs/probes/related-quality-results.md, post-hoc split) found YouTube's top related items are
// mostly compilations over 15 minutes: in the length window ranks 1 to 8 gave 8 tracks over 12
// seeds and ranks 9 to 20 gave 11 more, so the old cap of 8 left most in-window related songs
// unused. Whether the deeper ranks sit further from the seed is not measured.

/**
 * Related gate, absolute floor: an unverified related upload needs at least this many views.
 * GUESS: 10_000, the brief's starting value. The related-quality probe (2026-11-12) was
 * inconclusive by the rule its criteria fixed before the run (15 verified items, 20 required), so
 * the guess stays; that rule would have given 200_000 from the thin set. Inside the length window
 * the two values acted the same on that run: they dropped the only two unverified top-8 tracks
 * (79 and 2_800 views). Higher: drops real songs from small channels (fan uploads, niche genres),
 * and the Mix fills their share. Lower: lets near-unwatched uploads through (the probe's 79-view
 * clip).
 */
internal const val RELATED_MIN_VIEWS = 10_000L

/**
 * Related gate, relative floor: an unverified related upload also needs this share of the seed's
 * views. GUESS: 0.01, the brief's starting value, kept for the same inconclusive probe (its rule
 * would have given 0.0005). Next to a billion-view seed it asks for 10 M views. In the probe's
 * length window the two values disagreed once, outside the top 8: a 643_000-view upload of a real
 * song next to a 512 M-view seed (dropped at 0.01, kept at 0.0005). Higher: related next to big
 * hits becomes verified-only. Lower: lets small reposts next to big hits through.
 */
internal const val RELATED_MIN_SEED_FRACTION = 0.01

/** The related gate: a verified uploader passes; otherwise the views must reach both
 *  [RELATED_MIN_VIEWS] and [RELATED_MIN_SEED_FRACTION] of [seedViews]. Unknown views (-1) fail;
 *  unknown seed views skip the relative test only. Related videos only, never the Mix. */
internal fun passesRelatedGate(video: YtVideo, seedViews: Long): Boolean {
    if (video.uploaderVerified) return true
    val views = video.viewCount
    if (views < 0) return false
    return views >= RELATED_MIN_VIEWS &&
        (seedViews < 0 || views >= RELATED_MIN_SEED_FRACTION * seedViews)
}

/** The station's track length window, which Home's "More like" cards share. */
internal fun YtVideo.inTrackWindow(): Boolean = durationSeconds in StationEngine.MIN_TRACK_SECONDS..StationEngine.MAX_TRACK_SECONDS

/** One fetch from a song after the shared rules ([songLists]): the two lists in their own
 *  orders, and the counts the logs report. */
internal class SongLists(
    val mix: List<YtVideo>,
    val related: List<YtVideo>,
    /** Of the related videos, how many the gate kept and dropped. */
    val gateKept: Int,
    val gateDropped: Int,
    /** Tracks the one-copy-per-title rule removed. */
    val dupes: Int,
)

/**
 * The shared rules of a fetch from [seed]:
 * 1. Related: every related video, then the gate ([passesRelatedGate]), then uploads of the
 *    seed's own song (the same [TitleCleaner.key]: covers, lyric and live uploads that keep the
 *    title) leave.
 * 2. Both lists: the length window, [seed] itself (a Mix's first item is its own seed, RD probe
 *    Q4), [excludedIds] (the caller's: played this session, removed, queued, liked) and repeats;
 *    an id in both lists stays in the Mix.
 * 3. One copy per title: of tracks with the same [TitleCleaner.key] across both lists, only the
 *    most viewed stays (the first of a tie, Mix before related). A Mix item's views are unknown
 *    (-1), so a related upload with known views wins over its Mix copy.
 */
internal fun songLists(
    seed: YtVideo,
    mix: List<YtVideo>,
    related: YtRelated,
    excludedIds: Set<String>,
): SongLists {
    val gated = related.videos.filter { passesRelatedGate(it, related.seedViews) }
    val seedKey = TitleCleaner.key(seed.title, seed.uploader)
    val seen = HashSet<String>()
    fun allowed(video: YtVideo) = video.inTrackWindow() &&
        video.videoId != seed.videoId &&
        video.videoId !in excludedIds &&
        seen.add(video.videoId)
    val mixFresh = mix.filter(::allowed)
    val relatedFresh = gated
        .filter { seedKey == null || TitleCleaner.key(it.title, it.uploader) != seedKey }
        .filter(::allowed)

    val pool = mixFresh + relatedFresh
    val keys = pool.map { TitleCleaner.key(it.title, it.uploader) }
    val best = HashMap<String, Int>() // title key -> index in pool of the copy that stays
    pool.forEachIndexed { i, video ->
        val key = keys[i] ?: return@forEachIndexed
        val held = best[key]
        if (held == null || video.viewCount > pool[held].viewCount) best[key] = i
    }
    fun stays(i: Int) = keys[i]?.let { best[it] == i } ?: true
    val mixKept = mixFresh.filterIndexed { i, _ -> stays(i) }
    val relatedKept = relatedFresh.filterIndexed { i, _ -> stays(mixFresh.size + i) }
    return SongLists(
        mix = mixKept,
        related = relatedKept,
        gateKept = gated.size,
        gateDropped = related.videos.size - gated.size,
        dupes = pool.size - mixKept.size - relatedKept.size,
    )
}

/**
 * The pair merge: pairs of one [mix] and one [related] item while both lists last, which one
 * first drawn from [random] per pair; then the rest of the longer list. Each list keeps its own
 * order, and every pair boundary has taken as many of one list as of the other. When one list
 * is empty or runs short, the other fills its share.
 */
internal fun <T> pairUp(mix: List<T>, related: List<T>, random: Random): List<T> = buildList(mix.size + related.size) {
    val pairs = minOf(mix.size, related.size)
    for (i in 0 until pairs) {
        if (random.nextBoolean()) {
            add(mix[i])
            add(related[i])
        } else {
            add(related[i])
            add(mix[i])
        }
    }
    addAll(mix.subList(pairs, mix.size))
    addAll(related.subList(pairs, related.size))
}
