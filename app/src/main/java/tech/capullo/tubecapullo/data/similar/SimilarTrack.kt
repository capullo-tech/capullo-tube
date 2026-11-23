package tech.capullo.tubecapullo.data.similar

/** One Last.fm suggestion. [match] is Last.fm's 0..1 similarity (1 = closest). */
data class SimilarTrack(val artist: String, val title: String, val match: Double)

data class SimilarArtist(val name: String, val match: Double)

enum class SimilarDepth {
    /** Last.fm's closest songs first. */
    FAMILIAR,

    /** Songs past Last.fm's top [DEPTH_FAMILIAR_RANKS]: less similar, not necessarily less known. */
    DEEP,
}

/** GUESS: the probe saw ranks 1-10 hold the well-known neighbours of a seed. */
internal const val DEPTH_FAMILIAR_RANKS = 10

/**
 * The order to try Last.fm's suggestions in. Deep starts past the familiar ranks; the familiar
 * ranks follow so a short answer still yields songs. No match floor: the score is relative to
 * each list (Redonditos' list scores 0.007 at rank 11 for a good Babasónicos match), so a
 * fixed floor sent whole lists back to rank 1.
 */
internal fun depthOrder(tracks: List<SimilarTrack>, depth: SimilarDepth): List<SimilarTrack> = when (depth) {
    SimilarDepth.FAMILIAR -> tracks
    SimilarDepth.DEEP ->
        tracks.drop(DEPTH_FAMILIAR_RANKS) + tracks.take(DEPTH_FAMILIAR_RANKS)
}
