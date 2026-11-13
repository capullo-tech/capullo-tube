package tech.capullo.tubecapullo.station

import tech.capullo.tubecapullo.data.youtube.YtRelated
import tech.capullo.tubecapullo.data.youtube.YtVideo
import tech.capullo.tubecapullo.util.TitleCleaner
import kotlin.random.Random

internal const val RELATED_MIN_VIEWS = 10_000L

internal const val RELATED_MIN_SEED_FRACTION = 0.01

internal fun passesRelatedGate(video: YtVideo, seedViews: Long): Boolean {
    if (video.uploaderVerified) return true
    val views = video.viewCount
    if (views < 0) return false
    return views >= RELATED_MIN_VIEWS &&
        (seedViews < 0 || views >= RELATED_MIN_SEED_FRACTION * seedViews)
}

internal fun YtVideo.inTrackWindow(): Boolean = durationSeconds in StationEngine.MIN_TRACK_SECONDS..StationEngine.MAX_TRACK_SECONDS

internal class SongLists(
    val mix: List<YtVideo>,
    val related: List<YtVideo>,
    val gateKept: Int,
    val gateDropped: Int,
    val dupes: Int,
)

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
    val best = HashMap<String, Int>()
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
