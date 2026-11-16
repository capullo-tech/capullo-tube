package tech.capullo.tubecapullo.data.lyrics

enum class LyricsSource(val logName: String) {
    LRCLIB_GET("lrclib-get"),
    LRCLIB_SEARCH("lrclib-search"),
    DESCRIPTION("description"),
    ;

    companion object {
        fun fromLogName(name: String): LyricsSource? = entries.firstOrNull { it.logName == name }
    }
}

data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val source: LyricsSource, val synced: Boolean, val lines: List<LyricLine>) {

    companion object {

        fun synced(source: LyricsSource, lrc: String): Lyrics? = Lrc.parse(lrc)
            .takeIf { it.isNotEmpty() }
            ?.let { Lyrics(source, synced = true, lines = it) }

        fun plain(source: LyricsSource, text: String): Lyrics? {
            val lines = mutableListOf<LyricLine>()
            text.lineSequence().map { it.trim() }.forEach { line ->
                if (line.isNotEmpty() || lines.lastOrNull()?.text?.isNotEmpty() == true) {
                    lines += LyricLine(null, line)
                }
            }
            while (lines.lastOrNull()?.text?.isEmpty() == true) lines.removeAt(lines.lastIndex)
            return if (lines.isEmpty()) null else Lyrics(source, synced = false, lines = lines)
        }
    }
}

object Lrc {

    fun parse(lrc: String): List<LyricLine> = lrc.lineSequence()
        .flatMap { raw ->
            var rest = raw.trim()
            val times = mutableListOf<Long>()
            while (true) {
                val stamp = STAMP.find(rest) ?: break
                times += stampMs(stamp)
                rest = rest.substring(stamp.range.last + 1)
            }
            times.map { LyricLine(it, rest.trim()) }
        }
        .sortedBy { it.timeMs }
        .toList()

    fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val time = lines[mid].timeMs ?: return -1
            if (time <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    private fun stampMs(stamp: MatchResult): Long {
        val (minutes, seconds, fraction) = stamp.destructured

        val ms = fraction.padEnd(3, '0').toLong()
        return (minutes.toLong() * 60 + seconds.toLong()) * 1000 + ms
    }

    private val STAMP = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
}
