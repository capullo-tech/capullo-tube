package tech.capullo.tubecapullo.data.lyrics

/** Where a song's lyrics came from. [logName] is the `source=` of the TubeLyrics log line and
 *  the value the cache stores. */
enum class LyricsSource(val logName: String) {
    LRCLIB_GET("lrclib-get"),
    LRCLIB_SEARCH("lrclib-search"),
    DESCRIPTION("description"),
    ;

    companion object {
        fun fromLogName(name: String): LyricsSource? = entries.firstOrNull { it.logName == name }
    }
}

/** One line of lyrics. [timeMs] is where it starts in the song; null in plain lyrics. A blank
 *  [text] is a pause (an instrumental break in synced lyrics, a stanza break in plain ones). */
data class LyricLine(val timeMs: Long?, val text: String)

/** A song's lyrics: synced when every line has a time, in time order; plain otherwise. */
data class Lyrics(val source: LyricsSource, val synced: Boolean, val lines: List<LyricLine>) {

    companion object {
        /** Synced lyrics from LRC text ([Lrc.parse]); null when no line has a time stamp. */
        fun synced(source: LyricsSource, lrc: String): Lyrics? = Lrc.parse(lrc)
            .takeIf { it.isNotEmpty() }
            ?.let { Lyrics(source, synced = true, lines = it) }

        /** Plain lyrics, one line per text line. Runs of blank lines become one stanza break;
         *  none at the start or the end. Null when no line has text. */
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

/** LRC, the synced-lyrics text format LRCLIB serves: `[mm:ss.xx]line`. */
object Lrc {

    /**
     * The timed lines of [lrc], in time order. A line may carry several stamps
     * (`[00:12.00][01:05.30]chorus`): it is one line per stamp, with the same text. Stamps take
     * `mm:ss`, `mm:ss.x`, `mm:ss.xx` and `mm:ss.xxx` (also with `:` before the fraction). Tags
     * (`[ar:Artist]`, `[offset:+120]`) and text without a stamp are not lines.
     */
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

    /**
     * The index of the line playing at [positionMs]: the last line whose time is at or before
     * it. -1 before the first line, and always for plain lyrics. [lines] are in time order
     * ([parse]), so this is a binary search.
     */
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
        // ".5" is 500 ms, ".45" is 450 ms, ".456" is 456 ms.
        val ms = fraction.padEnd(3, '0').toLong()
        return (minutes.toLong() * 60 + seconds.toLong()) * 1000 + ms
    }

    /** One leading stamp; group 3 (the fraction) is empty for `[mm:ss]`. */
    private val STAMP = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
}
