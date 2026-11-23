package tech.capullo.tubecapullo.station

import tech.capullo.tubecapullo.data.similar.SongKey
import tech.capullo.tubecapullo.data.similar.SongNames
import tech.capullo.tubecapullo.data.similar.differentVersions
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
    val repeats: Int = 0,
)

internal fun songLists(
    seed: YtVideo,
    mix: List<YtVideo>,
    related: YtRelated,
    excludedIds: Set<String>,
    heard: List<YtVideo> = emptyList(),
): SongLists {
    val gated = related.videos.filter { passesRelatedGate(it, related.seedViews) }
    val seedKey = TitleCleaner.key(seed.title, seed.uploader)
    val heardSongs = (heard + seed).distinctBy { it.videoId }.map { it to SongNames(it) }
    var repeats = 0

    // Another upload of a song already played or queued (a lyric video, a re-upload) is a
    // repeat; a marked different version (live, acoustic, remix...) is not.
    fun repeatsHeard(video: YtVideo): Boolean {
        val song = SongKey.of(video) ?: return false
        val repeat = heardSongs.any { (other, names) ->
            other.videoId != video.videoId && names.matches(song) && !differentVersions(video.title, other.title)
        }
        if (repeat) repeats++
        return repeat
    }
    val seen = HashSet<String>()
    fun allowed(video: YtVideo) = video.inTrackWindow() &&
        video.videoId != seed.videoId &&
        video.videoId !in excludedIds &&
        !repeatsHeard(video) &&
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
        repeats = repeats,
    )
}

/**
 * One item of each list per round, the order inside a round random. With two lists this is the
 * original Mix/related pairing; a list that runs out drops out of later rounds.
 */
internal fun <T> interleave(lists: List<List<T>>, random: Random): List<T> = buildList(lists.sumOf { it.size }) {
    val rounds = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until rounds) {
        val round = lists.mapNotNull { it.getOrNull(i) }
        when (round.size) {
            1 -> add(round[0])
            2 -> if (random.nextBoolean()) addAll(round) else addAll(round.asReversed())
            else -> addAll(round.shuffled(random))
        }
    }
}
