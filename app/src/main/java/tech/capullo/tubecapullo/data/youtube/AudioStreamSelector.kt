package tech.capullo.tubecapullo.data.youtube

data class AudioCandidate(
    val url: String,
    val mimeType: String,
    val bitrateKbps: Int,
    val contentLength: Long?,
    val itag: Int,
    val isM4a: Boolean,
    val isProgressive: Boolean,
)

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
