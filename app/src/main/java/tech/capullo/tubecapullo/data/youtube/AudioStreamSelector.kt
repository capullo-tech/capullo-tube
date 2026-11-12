package tech.capullo.tubecapullo.data.youtube

/**
 * One audio-only stream YouTube offered, flattened off the extractor's types so the choice between
 * them is a pure function that can be unit-tested without a network or a mocked `AudioStream`.
 */
data class AudioCandidate(
    val url: String,
    val mimeType: String,
    val bitrateKbps: Int,
    val contentLength: Long?,
    val itag: Int,
    /** M4A/AAC. The alternative from YouTube is almost always WebM/Opus. */
    val isM4a: Boolean,
    /** A plain HTTP URL playable as-is. DASH/HLS variants need a manifest and are skipped. */
    val isProgressive: Boolean,
)

/**
 * Picks which audio stream to actually play.
 *
 * Opus at a given bitrate sounds better than AAC, so on pure audio quality WebM/Opus wins and
 * `preferM4a = false` is the streaming default. The M4A preference exists for callers that cache
 * to disk and read the file afterwards: telecloud's metadata/analysis pipeline (jaudiotagger,
 * `MediaExtractor`) parses M4A and cannot parse WebM/Opus. CapulloTube has no such pipeline yet -
 * the parameter is kept because the disk cache planned for the station's prefetch (M4) will face
 * the same choice, and the selector is shared logic, not app policy.
 */
object AudioStreamSelector {

    fun select(candidates: List<AudioCandidate>, preferM4a: Boolean): AudioCandidate? {
        val usable = candidates.filter { it.isProgressive && it.url.isNotBlank() }
        if (usable.isEmpty()) return null

        if (preferM4a) {
            usable.filter { it.isM4a }.maxByOrNull { it.bitrateKbps }?.let { return it }
        }
        return usable.maxByOrNull { it.bitrateKbps }
    }
}
