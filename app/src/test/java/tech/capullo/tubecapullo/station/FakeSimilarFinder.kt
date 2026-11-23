package tech.capullo.tubecapullo.station

import tech.capullo.tubecapullo.data.similar.SimilarFind
import tech.capullo.tubecapullo.data.similar.SimilarFinder
import tech.capullo.tubecapullo.data.similar.SimilarNames
import tech.capullo.tubecapullo.data.similar.SimilarTrack
import tech.capullo.tubecapullo.data.youtube.YtVideo

/**
 * Last.fm stand-in: [similar] maps a seed id to the videos its names resolve to. The default
 * finds nothing, so a radio is YouTube's Mix and related only.
 */
internal class FakeSimilarFinder(
    private val similar: (String) -> List<YtVideo> = { emptyList() },
) : SimilarFinder {
    val namedSeeds = mutableListOf<String>()
    val resolveCalls = mutableListOf<Triple<Set<String>, List<YtVideo>, Int>>()

    override suspend fun names(seed: YtVideo): SimilarNames {
        namedSeeds += seed.videoId
        val videos = similar(seed.videoId)
        return SimilarNames(videos.map { SimilarTrack(seed.videoId, it.videoId, 1.0) }, if (videos.isEmpty()) "none" else "track")
    }

    override suspend fun resolve(names: SimilarNames, knownIds: Set<String>, offered: List<YtVideo>, count: Int): SimilarFind {
        resolveCalls += Triple(knownIds, offered, count)
        val seed = names.tracks.firstOrNull()?.artist ?: return SimilarFind(emptyList(), 0, 0, 0)
        val named = names.tracks.map { it.title }
        val videos = similar(seed).filter { it.videoId in named && it.videoId !in knownIds }.take(count)
        return SimilarFind(videos, videos.size, videos.size, 0)
    }
}
