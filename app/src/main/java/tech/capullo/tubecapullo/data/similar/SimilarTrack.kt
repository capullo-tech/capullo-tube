package tech.capullo.tubecapullo.data.similar

/** One Last.fm suggestion. [match] is Last.fm's 0..1 similarity (1 = closest). */
data class SimilarTrack(val artist: String, val title: String, val match: Double)

data class SimilarArtist(val name: String, val match: Double)

enum class SimilarDepth {
    /** Last.fm's closest songs first. */
    FAMILIAR,

    /** Songs past Last.fm's top [DEPTH_FAMILIAR_RANKS] with a match of at least [DEEP_MATCH_FLOOR]. */
    DEEP,
}

/** GUESS: the probe saw ranks 1-10 hold the well-known neighbours of a seed. */
internal const val DEPTH_FAMILIAR_RANKS = 10

/** GUESS: below 0.1 the probe's Deep picks drifted (Rosalía -> Lana Del Rey at 0.056). */
internal const val DEEP_MATCH_FLOOR = 0.1

/**
 * The order to try Last.fm's suggestions in. Deep starts past the familiar ranks and drops
 * low-confidence matches; the familiar ranks follow so a thin answer still yields songs.
 */
internal fun depthOrder(tracks: List<SimilarTrack>, depth: SimilarDepth): List<SimilarTrack> = when (depth) {
    SimilarDepth.FAMILIAR -> tracks
    SimilarDepth.DEEP ->
        tracks.drop(DEPTH_FAMILIAR_RANKS).filter { it.match >= DEEP_MATCH_FLOOR } +
            tracks.take(DEPTH_FAMILIAR_RANKS)
}
